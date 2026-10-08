package com.bank.customer.infrastructure.outbox;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.NetworkException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SaslAuthenticationException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaProducerException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class OutboxRelayTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    private final SpringDataOutboxRepository outbox = mock(SpringDataOutboxRepository.class);
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final TransactionTemplate transactions = inlineTransactions();
    private final MutableClock clock = new MutableClock(NOW);
    /** Backoff after a stopped batch: from the poll interval, doubling, capped (customer.outbox.relay.backoff-max). */
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);
    private static final Duration BACKOFF_MAX = Duration.ofMinutes(5);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final OutboxRelay relay = new OutboxRelay(outbox, kafka, transactions,
        clock, 50, Duration.ofSeconds(1), Duration.ofDays(7), POLL_INTERVAL, BACKOFF_MAX, meters);

    /** A clock the test moves forward. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void anotherReplicaHoldingTheLockMeansNothingIsSent() {
        when(outbox.tryRelayLock(anyLong())).thenReturn(false);

        assertThat(relay.relayOnce()).isZero();
        verify(outbox, never()).findUnpublishedBatch(50);
        verify(kafka, never()).send(any(ProducerRecord.class));
    }

    @Test
    void aRetryableFailureStopsTheBatchSoLaterEventsCannotOvertakeIt() {
        OutboxEventJpaEntity first = row("CUST-1");
        OutboxEventJpaEntity second = row("CUST-1");
        OutboxEventJpaEntity third = row("CUST-2");
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(first, second, third));
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null))
            .thenReturn(CompletableFuture.failedFuture(producerFailure(new NetworkException("broker down"))));

        int sent = relay.relayOnce();

        assertThat(sent).isEqualTo(1);
        assertThat(first.getPublishedAt()).isEqualTo(NOW);
        assertThat(second.getPublishedAt()).isNull();
        assertThat(second.getParkedAt()).as("a retryable failure is retried, not parked").isNull();
        assertUntouched(second);
        assertThat(third.getAttempts()).isZero();
        verify(kafka, times(2)).send(any(ProducerRecord.class));
    }

    @Test
    void aSendThatTimesOutLocallyIsRetryable() {
        OutboxEventJpaEntity first = row("CUST-1");
        OutboxEventJpaEntity second = row("CUST-2");
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(first, second));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());

        assertThat(relay.relayOnce()).isZero();

        assertThat(first.getParkedAt()).isNull();
        assertUntouched(first);
        verify(kafka, times(1)).send(any(ProducerRecord.class));
    }

    /** Failures caused by the record itself: no retry can succeed, so the row parks at once. */
    static Stream<RuntimeException> payloadFailures() {
        return Stream.of(
            new RecordTooLargeException("too large"),
            new SerializationException("cannot serialize"),
            new InvalidTopicException("bad topic"));
    }

    /**
     * Failures of the relay's credentials or of unknown kind: they hit every
     * row alike and usually clear once the platform is fixed, so they stop the
     * batch like a retryable failure and are retried with backoff, never parked.
     */
    static Stream<RuntimeException> failuresThatStopTheBatch() {
        return Stream.of(
            new SaslAuthenticationException("SASL authentication failed"),
            new AuthenticationException("authentication failed"),
            new AuthorizationException("not authorized"),
            new TopicAuthorizationException(Set.of("evt.cus.customer.created.v1")),
            new KafkaException("unclassified producer failure"),
            new IllegalStateException("not a Kafka error"));
    }

    @ParameterizedTest
    @MethodSource("failuresThatStopTheBatch")
    void anAuthOrUnclassifiedFailureStopsTheBatchAndParksNothing(RuntimeException failure) {
        OutboxEventJpaEntity first = row("CUST-1");
        OutboxEventJpaEntity next = row("CUST-2");
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(first, next));
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.failedFuture(producerFailure(failure)))
            .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));

        assertThat(relay.relayOnce()).isZero();

        assertThat(first.getParkedAt()).as("not parked").isNull();
        assertUntouched(first);
        assertThat(next.getParkedAt()).isNull();
        assertThat(next.getPublishedAt()).as("the batch stopped").isNull();
        assertThat(next.getAttempts()).isZero();
        verify(kafka, times(1)).send(any(ProducerRecord.class));
    }

    @ParameterizedTest
    @MethodSource("payloadFailures")
    void aPayloadFailureParksTheRowAndTheBatchContinuesWithOtherCustomers(RuntimeException permanent) {
        OutboxEventJpaEntity poison = row("CUST-1");
        OutboxEventJpaEntity next = row("CUST-2");
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(poison, next));
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.failedFuture(producerFailure(permanent)))
            .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));

        assertThat(relay.relayOnce()).isEqualTo(1);

        assertThat(poison.getParkedAt()).isEqualTo(NOW);
        assertThat(poison.getPublishedAt()).isNull();
        assertThat(poison.getAttempts()).isEqualTo(1);
        assertThat(poison.getLastError()).startsWith(permanent.getClass().getSimpleName());
        assertThat(next.getPublishedAt()).isEqualTo(NOW);
        assertThat(next.getParkedAt()).isNull();
        verify(kafka, times(2)).send(any(ProducerRecord.class));
    }

    /**
     * A customer raises several events (created, credit reserved, released);
     * the ones behind a parked event of the same customer wait with it, so
     * a consumer never sees credit released before it was reserved.
     */
    @Test
    void laterEventsOfTheParkedCustomerAreHeldBackWhileOtherCustomersFlow() {
        OutboxEventJpaEntity poison = row("CUST-1");
        OutboxEventJpaEntity sameCustomer = row("CUST-1");
        OutboxEventJpaEntity otherCustomer = row("CUST-2");
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(poison, sameCustomer, otherCustomer));
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.failedFuture(producerFailure(new RecordTooLargeException("too large"))))
            .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));

        assertThat(relay.relayOnce()).isEqualTo(1);

        assertThat(poison.getParkedAt()).isEqualTo(NOW);
        assertThat(sameCustomer.getPublishedAt()).isNull();
        assertThat(sameCustomer.getParkedAt()).isNull();
        assertThat(sameCustomer.getAttempts()).isZero();
        assertThat(otherCustomer.getPublishedAt()).isEqualTo(NOW);
        verify(kafka, times(2)).send(any(ProducerRecord.class));
    }

    /**
     * A broker or egress outage is retryable however long it lasts in
     * attempts: 20 failures in a row (20 relay ticks) do not park the row,
     * so an outage never turns into a manual replay.
     */
    @Test
    void twentyRetryableFailuresInARowDoNotParkTheRow() {
        OutboxEventJpaEntity row = row("CUST-1");
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(row));
        when(kafka.send(any(ProducerRecord.class)))
            .thenAnswer(invocation -> CompletableFuture.failedFuture(producerFailure(new NetworkException("broker down"))));

        for (int tick = 0; tick < 20; tick++) {
            assertThat(relay.relayOnce()).isZero();
            clock.advance(BACKOFF_MAX);                      // past any backoff wait
        }

        assertThat(row.getParkedAt()).isNull();
        assertUntouched(row);
    }

    static Stream<RuntimeException> nonPayloadFailures() {
        return Stream.of(
            new NetworkException("broker down"),
            new TopicAuthorizationException(Set.of("evt.cus.customer.created.v1")),
            new SaslAuthenticationException("SASL authentication failed"),
            new KafkaException("unclassified producer failure"));
    }

    /**
     * ADR-021 decision 4: a retriable, authorization or unclassified failure
     * never parks or skips the row, however long it lasts; the batch stops at
     * it every time, so nothing after it is sent.
     */
    @ParameterizedTest
    @MethodSource("nonPayloadFailures")
    void aNonPayloadFailureNeverParksTheRowEvenAfterMoreThan24Hours(RuntimeException failure) {
        OutboxEventJpaEntity stuck = row("CUST-1");
        OutboxEventJpaEntity next = row("CUST-2");
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(stuck, next));
        when(kafka.send(any(ProducerRecord.class)))
            .thenAnswer(invocation -> CompletableFuture.failedFuture(producerFailure(failure)));

        for (Duration elapsed : List.of(Duration.ZERO, Duration.ofHours(24), BACKOFF_MAX, Duration.ofDays(7))) {
            clock.advance(elapsed);
            assertThat(relay.relayOnce()).isZero();
        }

        assertThat(stuck.getParkedAt()).as("never parked").isNull();
        assertUntouched(stuck);
        assertThat(next.getPublishedAt()).as("the next row is never sent").isNull();
        assertUntouched(next);
        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka, times(4)).send(sent.capture());
        assertThat(sent.getAllValues()).extracting(ProducerRecord::key).containsOnly("CUST-1");
    }

    /** ADR-021: the relay stops "without marking the row"; no attempt, error or first-failure time is written. */
    private static void assertUntouched(OutboxEventJpaEntity row) {
        assertThat(row.getAttempts()).as("attempts").isZero();
        assertThat(row.getLastError()).as("last_error").isNull();
        assertThat(row.getFirstFailedAt()).as("first_failed_at").isNull();
        assertThat(row.getParkedAt()).as("parked_at").isNull();
    }

    @Test
    void aNonRetriableFailureParksOnItsFirstAttempt() {
        OutboxEventJpaEntity poison = row("CUST-1");
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(poison));
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.failedFuture(producerFailure(new RecordTooLargeException("too large"))));

        relay.relayOnce();

        assertThat(poison.getAttempts()).isEqualTo(1);
        assertThat(poison.getParkedAt()).isEqualTo(NOW);
        assertThat(poison.getLastError()).startsWith("RecordTooLargeException");
        assertThat(poison.getFirstFailedAt()).as("no longer written (ADR-021 drops the ceiling)").isNull();
    }

    @Test
    void aStoppedBatchBacksOffExponentiallyAndASuccessResetsIt() {
        OutboxEventJpaEntity row = row("CUST-1");
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(row));
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.failedFuture(producerFailure(new NetworkException("broker down"))))
            .thenReturn(CompletableFuture.failedFuture(producerFailure(new NetworkException("broker down"))))
            .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null))
            .thenReturn(CompletableFuture.failedFuture(producerFailure(new NetworkException("broker down"))));

        relay.relayOnce();                                    // NOW: stopped, wait 1 s
        clock.advance(Duration.ofMillis(999));
        assertThat(relay.relayOnce()).isZero();               // still waiting: nothing tried
        verify(outbox, times(1)).tryRelayLock(anyLong());
        clock.advance(Duration.ofMillis(1));
        relay.relayOnce();                                    // NOW+1 s: stopped again, wait 2 s
        clock.advance(Duration.ofMillis(1999));
        relay.relayOnce();                                    // still waiting
        verify(kafka, times(2)).send(any(ProducerRecord.class));
        clock.advance(Duration.ofMillis(1));
        assertThat(relay.relayOnce()).isEqualTo(1);           // NOW+3 s: sent, wait reset
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(row("CUST-2")));
        relay.relayOnce();                                    // NOW+3 s: stopped, wait 1 s again
        assertThat(relay.backoff().nextAttemptAt()).isEqualTo(NOW.plusSeconds(4));
        verify(kafka, times(4)).send(any(ProducerRecord.class));
    }

    @Test
    void theBackoffIsCappedAtTheConfiguredMaximum() {
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(row("CUST-1")));
        when(kafka.send(any(ProducerRecord.class)))
            .thenAnswer(invocation -> CompletableFuture.failedFuture(producerFailure(new NetworkException("broker down"))));

        for (int run = 0; run < 12; run++) {
            relay.relayOnce();
            clock.advance(BACKOFF_MAX);
        }

        Instant lastFailure = clock.instant().minus(BACKOFF_MAX);
        assertThat(relay.backoff().nextAttemptAt()).isEqualTo(lastFailure.plus(BACKOFF_MAX));
        verify(kafka, times(12)).send(any(ProducerRecord.class));
    }

    /** outbox.send.failures (platform name, Prometheus outbox_send_failures_total), tagged with the failure's simple class name only (never ids or topics). */
    @Test
    void eachFailedSendIsCountedByExceptionClass() {
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(row("CUST-1"), row("CUST-2")));
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.failedFuture(producerFailure(new RecordTooLargeException("too large"))))
            .thenReturn(CompletableFuture.failedFuture(producerFailure(new NetworkException("broker down"))));

        relay.relayOnce();

        assertThat(meters.get("outbox.send.failures").tag("exception", "RecordTooLargeException").counter().count())
            .isEqualTo(1.0);
        assertThat(meters.get("outbox.send.failures").tag("exception", "NetworkException").counter().count())
            .isEqualTo(1.0);
        assertThat(meters.get("outbox.send.failures").meters())
            .extracting(Meter::getId)
            .allSatisfy(id -> assertThat(id.getTags()).extracting(tag -> tag.getKey()).containsExactly("exception"));
    }

    /** The platform alert rule is keyed on outbox.send.failures with exactly one tag, exception. */
    @Test
    void theRelayRegistersTheSendFailureCounterWithAnExceptionTag() {
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(row("CUST-1")));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.failedFuture(
            producerFailure(new TopicAuthorizationException(Set.of("evt.cus.customer.created.v1")))));

        relay.relayOnce();

        io.micrometer.core.instrument.Counter counter = meters.get("outbox.send.failures").counter();
        assertThat(counter.getId().getTags()).extracting(tag -> tag.getKey()).containsExactly("exception");
        assertThat(counter.getId().getTag("exception")).isEqualTo("TopicAuthorizationException");
        assertThat(meters.find("outbox.publish.failures").meters()).as("old name gone").isEmpty();
    }

    /** Review 5456301261: an authorization failure lasting days never parks the row, and nothing after it is sent. */
    @Test
    void aNonPayloadFailureNeverParksHoweverLongItLasts() {
        OutboxEventJpaEntity stuck = row("CUST-1");
        OutboxEventJpaEntity next = row("CUST-2");
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(stuck, next));
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(invocation -> CompletableFuture.failedFuture(
            producerFailure(new TopicAuthorizationException(Set.of("evt.cus.customer.created.v1")))));

        for (int hour = 0; hour < 24 * 5; hour++) {                   // hourly for five days
            assertThat(relay.relayOnce()).isZero();
            clock.advance(Duration.ofHours(1));
        }

        assertThat(stuck.getParkedAt()).isNull();
        assertThat(next.getPublishedAt()).isNull();
        verify(kafka, times(24 * 5)).send(any(ProducerRecord.class));
    }

    /** Review 5456301261: no attempts, first_failed_at or last_error; the error goes to the log only. */
    @Test
    void aNonPayloadFailureMarksNothingOnTheRow() {
        OutboxEventJpaEntity row = row("CUST-1");
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(row));
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.failedFuture(producerFailure(new NetworkException("broker down"))));

        relay.relayOnce();

        assertUntouched(row);
        assertThat(row.getPublishedAt()).isNull();
    }

    /**
     * Platform ruling (16:43Z): outbox.parked.events (Prometheus
     * outbox_parked_events_total) counts each parked row once, tagged with
     * the root cause's simple class name; the row is marked counted in the
     * same update that parks it.
     */
    @Test
    void aRelayParkIncrementsTheParkedCounterWithTheExceptionTag() {
        OutboxEventJpaEntity poison = row("CUST-1");
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(poison), List.of());
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.failedFuture(producerFailure(new RecordTooLargeException("too large"))));

        relay.relayOnce();
        relay.relayOnce();

        assertThat(poison.isParkCounted()).isTrue();
        assertThat(meters.get("outbox.parked.events").counters()).hasSize(1);
        io.micrometer.core.instrument.Counter parked = meters.get("outbox.parked.events").counter();
        assertThat(parked.getId().getTags()).extracting(tag -> tag.getKey()).containsExactly("exception");
        assertThat(parked.getId().getTag("exception")).isEqualTo("RecordTooLargeException");
        assertThat(parked.count()).as("once per row, never again on later ticks").isEqualTo(1.0);
    }

    /** An operator park (runbook UPDATE, park_counted false) is counted once by the relay, as OperatorPark. */
    @Test
    void anOperatorParkIsCountedExactlyOnceAsOperatorPark() {
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        // The bulk update flips park_counted on the one operator-parked row on the first tick only.
        when(outbox.markOperatorParksCounted()).thenReturn(1, 0, 0);

        for (int tick = 0; tick < 3; tick++) {
            relay.relayOnce();
        }

        verify(outbox, times(3)).markOperatorParksCounted();
        assertThat(meters.get("outbox.parked.events").tag("exception", "OperatorPark").counter().count()).isEqualTo(1.0);
        assertThat(meters.get("outbox.parked.events").counters()).hasSize(1);
    }

    /** What KafkaTemplate completes its future with when the producer reports a failure. */
    private static KafkaProducerException producerFailure(Throwable cause) {
        return new KafkaProducerException(new ProducerRecord<>("evt.cus.customer.created.v1", "k", "v"),
            "Failed to send", cause);
    }

    @Test
    void recordIsKeyedByAggregateAndCarriesTracingHeaders() {
        OutboxEventJpaEntity row = row("CUST-9");

        ProducerRecord<String, String> record = OutboxRelay.toRecord(row);

        assertThat(record.topic()).isEqualTo("evt.cus.customer.created.v1");
        assertThat(record.key()).isEqualTo("CUST-9");
        assertThat(record.value()).isEqualTo("{}");
        assertThat(header(record, "eventType")).isEqualTo("Customer.Customer.Created.v1");
        assertThat(header(record, "eventId")).isEqualTo(row.getEventId().toString());
        assertThat(header(record, "x-fapi-interaction-id")).isEqualTo("corr-9");
        assertThat(record.headers().lastHeader("traceparent")).isNull();
    }

    @Test
    void recordCarriesTheW3cTraceparentOfTheRequestThatRaisedTheEvent() {
        String traceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

        ProducerRecord<String, String> record = OutboxRelay.toRecord(row("CUST-9").withTraceparent(traceparent));

        assertThat(header(record, "traceparent")).isEqualTo(traceparent);
    }

    @Test
    void purgeDeletesRowsPublishedBeforeTheRetentionWindow() {
        when(outbox.deletePublishedBefore(NOW.minus(Duration.ofDays(7)))).thenReturn(3);

        assertThat(relay.purgePublished()).isEqualTo(3);
    }

    private static String header(ProducerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    private static OutboxEventJpaEntity row(String aggregateId) {
        return new OutboxEventJpaEntity(UUID.randomUUID(), "Customer", aggregateId, 0L,
            "Customer.Customer.Created.v1", "evt.cus.customer.created.v1", "{}", "corr-9", NOW);
    }

    private static TransactionTemplate inlineTransactions() {
        return new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
    }
}
