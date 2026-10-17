package com.bank.customer.infrastructure.outbox;

import com.bank.customer.infrastructure.persistence.PostgresTestDatabase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The relay's batch query on PostgreSQL: parked rows are skipped, the
 * parked customer's later events wait behind it, other customers flow.
 */
@DataJpaTest(properties = {
    "spring.datasource.hikari.schema=sc_cus_profile_kyc",
    "spring.flyway.schemas=sc_cus_profile_kyc",
    "spring.flyway.default-schema=sc_cus_profile_kyc",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.properties.hibernate.default_schema=sc_cus_profile_kyc"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OutboxPersistenceTest {

    private static final Instant NOW = Instant.parse("2026-10-08T08:00:00Z");

    @BeforeAll
    static void requireDatabase() {
        PostgresTestDatabase.assumeAvailable();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresTestDatabase.register(registry);
    }

    @Autowired SpringDataOutboxRepository outbox;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

    /**
     * The runbook's operator-only manual park (a row stuck on a non-payload
     * error, which the relay never parks itself under ADR-021 decision 4) and
     * its replay, run exactly as written in the runbook.
     */
    @Test
    void theRunbooksManualParkAndReplayStatementsWork() throws IOException {
        OutboxEventJpaEntity stuck = outbox.saveAndFlush(row("CUST-MANUAL-1"));
        String id = stuck.getEventId().toString();
        String manualPark = runbookStatement("-- Manual park (operator only)")
            .replace("<event id>", id).replace("<reason>", "TopicAuthorizationException since 09:00, CHG-1");
        String replay = runbookStatement("-- Un-park one row").replace("<event id>", id);

        assertThat(jdbc.update(manualPark)).isEqualTo(1);

        assertThat(jdbc.queryForObject("select last_error from sc_cus_profile_kyc.outbox_event where event_id = ?::uuid",
            String.class, id)).isEqualTo("manual: TopicAuthorizationException since 09:00, CHG-1");
        assertThat(jdbc.queryForObject("select parked_at is not null from sc_cus_profile_kyc.outbox_event where event_id = ?::uuid",
            Boolean.class, id)).isTrue();
        assertThat(outbox.findUnpublishedBatch(10)).extracting(OutboxEventJpaEntity::getEventId).doesNotContain(stuck.getEventId());
        assertThat(jdbc.update(manualPark)).as("parking twice changes nothing").isZero();

        assertThat(jdbc.update(replay)).isEqualTo(1);

        assertThat(jdbc.queryForObject("select parked_at is null and last_error is null and attempts = 0 "
            + "from sc_cus_profile_kyc.outbox_event where event_id = ?::uuid", Boolean.class, id)).isTrue();
        assertThat(outbox.findUnpublishedBatch(10)).extracting(OutboxEventJpaEntity::getEventId).contains(stuck.getEventId());
    }

    /**
     * PostgreSQL: a row parked with the runbook UPDATE is counted once by the
     * relay (OperatorPark) and marked park_counted; later ticks do not count
     * it again, and the runbook replay clears the mark.
     */
    @Test
    @SuppressWarnings("unchecked")
    void anOperatorParkIsCountedOnceByTheRelay() throws IOException {
        OutboxEventJpaEntity stuck = outbox.saveAndFlush(row("CUST-MANUAL-2"));
        String id = stuck.getEventId().toString();
        jdbc.update(runbookStatement("-- Manual park (operator only)").replace("<event id>", id).replace("<reason>", "CHG-2"));
        io.micrometer.core.instrument.simple.SimpleMeterRegistry meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        OutboxRelay relay = new OutboxRelay(outbox, org.mockito.Mockito.mock(org.springframework.kafka.core.KafkaTemplate.class),
            new org.springframework.transaction.support.TransactionTemplate(transactionManager), java.time.Clock.systemUTC(), 10,
            java.time.Duration.ofSeconds(1), java.time.Duration.ofDays(7), java.time.Duration.ofSeconds(1),
            java.time.Duration.ofMinutes(5), meters);

        relay.relayOnce();
        relay.relayOnce();

        assertThat(meters.get("outbox.parked.events").tag("exception", "OperatorPark").counter().count()).isEqualTo(1.0);
        assertThat(jdbc.queryForObject("select park_counted from sc_cus_profile_kyc.outbox_event where event_id = ?::uuid",
            Boolean.class, id)).isTrue();
        assertThat(jdbc.update(runbookStatement("-- Un-park one row").replace("<event id>", id))).as("replayed rows").isEqualTo(1);
        assertThat(jdbc.queryForObject("select park_counted from sc_cus_profile_kyc.outbox_event where event_id = ?::uuid",
            Boolean.class, id)).as("replay clears the mark so a later park counts again").isFalse();
    }

    /** The SQL statement that follows the given comment line in the runbook's "Parked outbox events" section. */
    private static String runbookStatement(String commentPrefix) throws IOException {
        List<String> lines = Files.readAllLines(Path.of("..", "docs", "migration", "RUNBOOK-EXTRACT-cus-profile-kyc.md"));
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(commentPrefix)) {
                start = i;
                break;
            }
        }
        assertThat(start).as("runbook has a statement after '%s'", commentPrefix).isNotNegative();
        StringBuilder sql = new StringBuilder();
        for (int i = start; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.startsWith("--") || line.isEmpty()) {
                continue;
            }
            sql.append(line).append('\n');
            if (line.endsWith(";")) {
                break;
            }
        }
        return sql.toString();
    }

    @Test
    void theBatchSkipsParkedRowsAndHoldsTheParkedCustomersLaterEvents() {
        OutboxEventJpaEntity parked = outbox.saveAndFlush(row("CUST-PARK-1"));
        OutboxEventJpaEntity behindParked = outbox.saveAndFlush(row("CUST-PARK-1"));
        OutboxEventJpaEntity otherCustomer = outbox.saveAndFlush(row("CUST-PARK-2"));
        parked.markFailed("RecordTooLargeException: too large", NOW);
        parked.park(NOW);
        outbox.saveAndFlush(parked);

        assertThat(outbox.findUnpublishedBatch(10))
            .extracting(OutboxEventJpaEntity::getEventId)
            .contains(otherCustomer.getEventId())
            .doesNotContain(parked.getEventId(), behindParked.getEventId());
        assertThat(outbox.countByPublishedAtIsNullAndParkedAtIsNotNull()).isEqualTo(1);
        assertThat(outbox.oldestPendingCreatedAt()).as("created_at of the oldest row waiting for the relay")
            .isEqualTo(jdbc.queryForObject("select min(created_at) from sc_cus_profile_kyc.outbox_event "
                + "where published_at is null and parked_at is null", java.sql.Timestamp.class).toInstant());
        assertThat(outbox.countByPublishedAtIsNullAndParkedAtIsNull()).isGreaterThanOrEqualTo(2);
        assertThat(outbox.findById(parked.getEventId())).get()
            .satisfies(found -> {
                assertThat(found.getParkedAt()).isEqualTo(NOW);
                assertThat(found.getLastError()).startsWith("RecordTooLargeException");
                assertThat(found.getFirstFailedAt()).as("kept as a column, no longer written").isNull();
            });
    }

    private static OutboxEventJpaEntity row(String aggregateId) {
        return new OutboxEventJpaEntity(UUID.randomUUID(), "Customer", aggregateId, 0L,
            "Customer.Customer.Created.v1", "evt.cus.customer.v1", "{}", "corr-park", NOW);
    }
}
