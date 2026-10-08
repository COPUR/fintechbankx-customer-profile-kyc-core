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
    void relayTakesItsBackoffFromSettings() {
        OutboxRelay relay = new OutboxConfiguration.RelayConfiguration().outboxRelay(outbox, mock(KafkaTemplate.class),
            mock(PlatformTransactionManager.class), Clock.systemUTC(), 100, Duration.ofSeconds(10), Duration.ofDays(7),
            Duration.ofSeconds(1), Duration.ofMinutes(5), new SimpleMeterRegistry());

        assertThat(relay.backoff().initial()).as("starts from the poll interval").isEqualTo(Duration.ofSeconds(1));
        assertThat(relay.backoff().max()).as("customer.outbox.relay.backoff-max").isEqualTo(Duration.ofMinutes(5));
    }

    /** Age (from created_at) of the oldest event waiting for the relay; 0 when nothing waits. The alert signal. */
    @Test
    void oldestPendingAgeGaugeIsExportedInSeconds() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        java.time.Instant now = java.time.Instant.parse("2026-10-08T10:00:00Z");
        Clock clock = Clock.fixed(now, java.time.ZoneOffset.UTC);
        when(outbox.oldestPendingCreatedAt()).thenReturn(now.minusSeconds(90), (java.time.Instant) null);

        configuration.customerOutboxOldestPendingAgeGauge(registry, outbox, clock);

        Gauge gauge = registry.get("outbox.oldest.pending.age.seconds").gauge();
        assertThat(gauge.value()).isEqualTo(90.0);
        assertThat(gauge.value()).as("nothing pending").isZero();
    }

    /**
     * The relay is off unless switched on: without customer.outbox.relay.enabled
     * there is no relay bean (and no schedule); events wait in the outbox.
     */
    @Test
    void theRelayIsAbsentUnlessItIsSwitchedOn() {
        org.springframework.boot.test.context.runner.ApplicationContextRunner runner =
            new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withInitializer(context -> context.getBeanFactory().setConversionService(
                    org.springframework.boot.convert.ApplicationConversionService.getSharedInstance()))
                .withUserConfiguration(OutboxConfiguration.RelayConfiguration.class)
                .withBean(SpringDataOutboxRepository.class, () -> outbox)
                .withBean(KafkaTemplate.class, () -> mock(KafkaTemplate.class))
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withBean(Clock.class, Clock::systemUTC)
                .withBean(io.micrometer.core.instrument.MeterRegistry.class, SimpleMeterRegistry::new);

        runner.run(context -> assertThat(context).doesNotHaveBean(OutboxRelay.class));
        runner.withPropertyValues("customer.outbox.relay.enabled=false")
            .run(context -> assertThat(context).doesNotHaveBean(OutboxRelay.class));
        runner.withPropertyValues("customer.outbox.relay.enabled=true")
            .run(context -> assertThat(context).hasSingleBean(OutboxRelay.class));
    }

    /** application.yml maps OUTBOX_RELAY_ENABLED with a false default; local runs and the chart switch it on. */
    @Test
    void theApplicationDefaultKeepsTheRelayOff() throws java.io.IOException {
        String yaml = java.nio.file.Files.readString(java.nio.file.Path.of(
            "..", "customer-bootstrap", "src", "main", "resources", "application.yml"));

        assertThat(yaml).contains("enabled: ${OUTBOX_RELAY_ENABLED:false}");
    }
}
