package com.bank.customer.infrastructure.outbox;

import com.bank.customer.infrastructure.persistence.PostgresTestDatabase;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Two relays (two replicas) running relayOnce at the same time on the same
 * PostgreSQL outbox: the transaction-scoped advisory lock lets one relay a
 * batch while the other skips, so every row reaches Kafka exactly once.
 * Without the lock both read the same unpublished rows and send them twice.
 */
@DataJpaTest(properties = {
    "spring.datasource.hikari.schema=sc_cus_profile_kyc",
    "spring.flyway.schemas=sc_cus_profile_kyc",
    "spring.flyway.default-schema=sc_cus_profile_kyc",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.properties.hibernate.default_schema=sc_cus_profile_kyc"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OutboxRelayConcurrencyTest {

    private static final int ROWS = 40;
    private static final Instant NOW = Instant.parse("2026-10-08T09:00:00Z");

    @BeforeAll
    static void requireDatabase() {
        PostgresTestDatabase.assumeAvailable();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresTestDatabase.register(registry);
    }

    @Autowired SpringDataOutboxRepository outbox;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void freshOutbox() {
        outbox.deleteAll();
    }

    @Test
    @SuppressWarnings("unchecked")
    void twoRelaysRunningTogetherSendEachRowExactlyOnce() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < ROWS; i++) {
            ids.add(outbox.saveAndFlush(new OutboxEventJpaEntity(UUID.randomUUID(), "Customer", "CUST-RELAY-" + (i % 7), i,
                "Customer.Customer.Created.v1", "evt.cus.customer.created.v1", "{}", "corr-relay", NOW)).getEventId());
        }
        Map<String, AtomicInteger> sends = new ConcurrentHashMap<>();
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            ProducerRecord<String, String> record = invocation.getArgument(0);
            String eventId = new String(record.headers().lastHeader("eventId").value(), StandardCharsets.UTF_8);
            sends.computeIfAbsent(eventId, id -> new AtomicInteger()).incrementAndGet();
            Thread.sleep(5); // a broker round trip, so the two relays overlap
            return CompletableFuture.completedFuture((SendResult<String, String>) null);
        });

        ExecutorService replicas = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> runs = new ArrayList<>();
            for (int replica = 0; replica < 2; replica++) {
                OutboxRelay relay = new OutboxRelay(outbox, kafka, new TransactionTemplate(transactionManager),
                    Clock.systemUTC(), 10, Duration.ofSeconds(5), Duration.ofDays(7), Duration.ofHours(24),
            Duration.ofSeconds(1), Duration.ofMinutes(5), new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
                runs.add(replicas.submit(() -> {
                    start.await();
                    int published = 0;
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                    while (outbox.countByPublishedAtIsNullAndParkedAtIsNull() > 0 && System.nanoTime() < deadline) {
                        published += relay.relayOnce();
                    }
                    return published;
                }));
            }
            start.countDown();
            int total = 0;
            for (Future<Integer> run : runs) {
                total += run.get(60, TimeUnit.SECONDS);
            }

            assertThat(outbox.countByPublishedAtIsNull()).isZero();
            assertThat(sends.keySet()).containsExactlyInAnyOrderElementsOf(ids.stream().map(UUID::toString).toList());
            assertThat(sends.values()).as("sends per event").allSatisfy(count -> assertThat(count.get()).isEqualTo(1));
            assertThat(total).isEqualTo(ROWS);
        } finally {
            replicas.shutdownNow();
        }
    }
}
