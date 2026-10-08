package com.bank.customer.infrastructure.outbox;

import com.bank.customer.infrastructure.persistence.PostgresTestDatabase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
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

    @Test
    void theBatchSkipsParkedRowsAndHoldsTheParkedCustomersLaterEvents() {
        OutboxEventJpaEntity parked = outbox.saveAndFlush(row("CUST-PARK-1"));
        OutboxEventJpaEntity behindParked = outbox.saveAndFlush(row("CUST-PARK-1"));
        OutboxEventJpaEntity otherCustomer = outbox.saveAndFlush(row("CUST-PARK-2"));
        parked.markFailed("RecordTooLargeException: too large");
        parked.park(NOW);
        outbox.saveAndFlush(parked);

        assertThat(outbox.findUnpublishedBatch(10))
            .extracting(OutboxEventJpaEntity::getEventId)
            .contains(otherCustomer.getEventId())
            .doesNotContain(parked.getEventId(), behindParked.getEventId());
        assertThat(outbox.countByPublishedAtIsNullAndParkedAtIsNotNull()).isEqualTo(1);
        assertThat(outbox.countByPublishedAtIsNullAndParkedAtIsNull()).isGreaterThanOrEqualTo(2);
        assertThat(outbox.findById(parked.getEventId())).get()
            .satisfies(found -> {
                assertThat(found.getParkedAt()).isEqualTo(NOW);
                assertThat(found.getLastError()).startsWith("RecordTooLargeException");
            });
    }

    private static OutboxEventJpaEntity row(String aggregateId) {
        return new OutboxEventJpaEntity(UUID.randomUUID(), "Customer", aggregateId, 0L,
            "Customer.Customer.Created.v1", "evt.cus.customer.created.v1", "{}", "corr-park", NOW);
    }
}
