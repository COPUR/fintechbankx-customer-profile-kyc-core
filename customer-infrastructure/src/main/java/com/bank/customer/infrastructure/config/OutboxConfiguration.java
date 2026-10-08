package com.bank.customer.infrastructure.config;

import com.bank.customer.infrastructure.outbox.CustomerEventEnvelopeFactory;
import com.bank.customer.infrastructure.outbox.OutboxRelay;
import com.bank.customer.infrastructure.outbox.SpringDataOutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Configuration
public class OutboxConfiguration {

    @Bean
    CustomerEventEnvelopeFactory customerEventEnvelopeFactory(ObjectMapper objectMapper) {
        return new CustomerEventEnvelopeFactory(objectMapper);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Backlog of events not yet on Kafka. Alert on growth: it means the relay
     * or the brokers are down while customers keep changing. Exported as
     * outbox_pending_events (platform name), tagged service=svc-cus-profile-kyc.
     */
    @Bean
    Gauge customerOutboxPendingGauge(MeterRegistry registry, SpringDataOutboxRepository outbox) {
        return Gauge.builder("outbox.pending.events", outbox, SpringDataOutboxRepository::countByPublishedAtIsNullAndParkedAtIsNull)
            .description("Customer events written to the outbox and waiting for the relay (parked rows excluded)")
            .register(registry);
    }

    /**
     * Events the relay gave up on (Prometheus outbox_parked_events). Alert on
     * any value above zero: consumers miss that customer's events until the
     * row is replayed (runbook "Parked outbox events").
     */
    @Bean
    Gauge customerOutboxParkedGauge(MeterRegistry registry, SpringDataOutboxRepository outbox) {
        return Gauge.builder("outbox.parked.events", outbox, SpringDataOutboxRepository::countByPublishedAtIsNullAndParkedAtIsNotNull)
            .description("Customer events parked after a payload error, or by an operator")
            .register(registry);
    }

    /**
     * Age of the oldest event waiting for the relay, 0 when none waits
     * (Prometheus outbox_oldest_pending_age_seconds), measured from created_at.
     * The platform alert fires when it stays above 15 minutes (ADR-021 decision 4): the relay or Kafka is down,
     * or a row keeps failing on a non-payload error and holds the batch.
     */
    @Bean
    Gauge customerOutboxOldestPendingAgeGauge(MeterRegistry registry, SpringDataOutboxRepository outbox, Clock clock) {
        return Gauge.builder("outbox.oldest.pending.age.seconds", outbox, repository -> {
                Instant oldest = repository.oldestPendingCreatedAt();
                return oldest == null ? 0.0 : Math.max(0, Duration.between(oldest, clock.instant()).toSeconds());
            })
            .description("Age in seconds of the oldest customer event waiting for the outbox relay")
            .baseUnit("seconds")
            .register(registry);
    }

    /**
     * The relay runs in every replica; the advisory lock lets only one of
     * them publish at a time. Disable with customer.outbox.relay.enabled=false
     * (tests, or a dedicated relay deployment).
     */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "customer.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
    static class RelayConfiguration {

        @Bean
        OutboxRelay outboxRelay(SpringDataOutboxRepository outbox,
                                KafkaTemplate<String, String> kafka,
                                PlatformTransactionManager transactionManager,
                                Clock clock,
                                @Value("${customer.outbox.relay.batch-size:100}") int batchSize,
                                @Value("${customer.outbox.relay.send-timeout:PT35S}") Duration sendTimeout,
                                @Value("${customer.outbox.retention:P7D}") Duration retention,
                                @Value("${customer.outbox.relay.interval:PT1S}") Duration pollInterval,
                                @Value("${customer.outbox.relay.backoff-max:PT5M}") Duration backoffMax,
                                MeterRegistry meters) {
            return new OutboxRelay(outbox, kafka, new TransactionTemplate(transactionManager), clock, batchSize,
                sendTimeout, retention, pollInterval, backoffMax, meters);
        }

        @Bean
        RelaySchedule relaySchedule(OutboxRelay relay) {
            return new RelaySchedule(relay);
        }
    }

    static class RelaySchedule {
        private final OutboxRelay relay;

        RelaySchedule(OutboxRelay relay) {
            this.relay = relay;
        }

        @Scheduled(fixedDelayString = "${customer.outbox.relay.interval:PT1S}")
        void relay() {
            relay.relayOnce();
        }

        @Scheduled(cron = "${customer.outbox.purge-cron:0 15 3 * * *}")
        void purge() {
            relay.purgePublished();
        }
    }
}
