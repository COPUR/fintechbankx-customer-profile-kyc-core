package com.bank.customer.infrastructure.outbox;

import com.bank.customer.infrastructure.persistence.PostgresTestDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-019 s8: outbox rows written before the move to one topic per aggregate
 * hold a per-event topic (evt.cus.customer.created.v1, ...). Rows that are
 * still to be sent, pending or parked, are rewritten to evt.cus.customer.v1;
 * published rows keep the topic they were sent to. Runs the real migrations
 * in a scratch schema: up to V10, then to the latest version.
 */
@DataJpaTest(properties = {
    "spring.datasource.hikari.schema=sc_cus_profile_kyc",
    "spring.flyway.schemas=sc_cus_profile_kyc",
    "spring.flyway.default-schema=sc_cus_profile_kyc",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.properties.hibernate.default_schema=sc_cus_profile_kyc"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// Not in a test transaction: Flyway migrates on its own connection, and rows the
// test inserted must be committed, or V11's UPDATE and ALTER TABLE wait on them.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OutboxAggregateTopicMigrationTest {

    private static final String SCHEMA = "sc_cus_topic_migration_test";

    @BeforeAll
    static void requireDatabase() {
        PostgresTestDatabase.assumeAvailable();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresTestDatabase.register(registry);
    }

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;

    @Test
    void pendingAndParkedRowsMoveToTheAggregateTopicAndPublishedRowsKeepTheirs() {
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        flyway("10").migrate();
        UUID pending = insert("evt.cus.customer.created.v1", false, false);
        UUID parked = insert("evt.cus.customer.credit-reserved.v1", false, true);
        UUID published = insert("evt.cus.customer.kyc-status-changed.v1", true, false);

        flyway(null).migrate();

        assertThat(topic(pending)).isEqualTo("evt.cus.customer.v1");
        assertThat(topic(parked)).isEqualTo("evt.cus.customer.v1");
        assertThat(topic(published)).as("history of what was sent").isEqualTo("evt.cus.customer.kyc-status-changed.v1");
        assertThatThrownBy(() -> insert("evt.cus.customer.created.v1", false, false))
            .as("a new pending row with a per-event topic is refused")
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(topic(insert("evt.cus.customer.v1", false, false))).isEqualTo("evt.cus.customer.v1");

        jdbc.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
    }

    private Flyway flyway(String target) {
        var config = Flyway.configure().dataSource(dataSource).schemas(SCHEMA).defaultSchema(SCHEMA)
            .placeholders(PostgresTestDatabase.flywayPlaceholders())
            .locations("classpath:db/migration");
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    private UUID insert(String topic, boolean published, boolean parked) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + SCHEMA + ".outbox_event (event_id, aggregate_type, aggregate_id, aggregate_version, "
                + "event_type, topic, payload, correlation_id, occurred_at, published_at, parked_at) "
                + "VALUES (?, 'Customer', 'CUST-MIG-1', 0, 'Customer.Customer.Created.v1', ?, '{}'::jsonb, 'corr-mig', now(), "
                + (published ? "now()" : "NULL") + ", " + (parked ? "now()" : "NULL") + ")",
            id, topic);
        return id;
    }

    private String topic(UUID id) {
        Map<String, Object> row = jdbc.queryForMap("SELECT topic FROM " + SCHEMA + ".outbox_event WHERE event_id = ?", id);
        return (String) row.get("topic");
    }
}
