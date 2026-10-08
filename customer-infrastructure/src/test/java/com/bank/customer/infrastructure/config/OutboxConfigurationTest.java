package com.bank.customer.infrastructure.config;

import com.bank.customer.infrastructure.outbox.OutboxRelay;
import com.bank.customer.infrastructure.outbox.SpringDataOutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutboxConfigurationTest {

    private final OutboxConfiguration configuration = new OutboxConfiguration();
    private final SpringDataOutboxRepository outbox = mock(SpringDataOutboxRepository.class);

    @Test
    void pendingGaugeCountsRowsWaitingForTheRelayNotParkedOnes() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(outbox.countByPublishedAtIsNullAndParkedAtIsNull()).thenReturn(4L);

        configuration.customerOutboxPendingGauge(registry, outbox);

        Gauge gauge = registry.get("outbox.pending.events").gauge();
        assertThat(gauge.value()).isEqualTo(4.0);
    }

    @Test
    void parkedGaugeIsExportedAsOutboxParkedEvents() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(outbox.countByPublishedAtIsNullAndParkedAtIsNotNull()).thenReturn(2L);

        configuration.customerOutboxParkedGauge(registry, outbox);

        Gauge gauge = registry.get("outbox.parked.events").gauge();
        assertThat(gauge.value()).isEqualTo(2.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void relayTakesItsRetryableCeilingFromSettings() {
        OutboxRelay relay = new OutboxConfiguration.RelayConfiguration().outboxRelay(outbox, mock(KafkaTemplate.class),
            mock(PlatformTransactionManager.class), Clock.systemUTC(), 100, Duration.ofSeconds(10), Duration.ofDays(7),
            Duration.ofHours(24));

        assertThat(relay.retryableParkAfter()).isEqualTo(Duration.ofHours(24));
    }

    /** Age of the oldest event waiting for the relay; 0 when nothing waits. Alert when it keeps growing. */
    @Test
    void oldestPendingAgeGaugeIsExportedInSeconds() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        java.time.Instant now = java.time.Instant.parse("2026-10-08T10:00:00Z");
        Clock clock = Clock.fixed(now, java.time.ZoneOffset.UTC);
        when(outbox.oldestPendingOccurredAt()).thenReturn(now.minusSeconds(90), (java.time.Instant) null);

        configuration.customerOutboxOldestPendingAgeGauge(registry, outbox, clock);

        Gauge gauge = registry.get("outbox.oldest.pending.age.seconds").gauge();
        assertThat(gauge.value()).isEqualTo(90.0);
        assertThat(gauge.value()).as("nothing pending").isZero();
    }
}
