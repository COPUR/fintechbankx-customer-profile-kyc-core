package com.bank.customer.infrastructure.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.NetworkException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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
    private static final int MAX_ATTEMPTS = 3;
    private final OutboxRelay relay = new OutboxRelay(outbox, kafka, transactions,
        Clock.fixed(NOW, ZoneOffset.UTC), 50, Duration.ofSeconds(1), Duration.ofDays(7), MAX_ATTEMPTS);

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
        assertThat(second.getAttempts()).isEqualTo(1);
        assertThat(second.getLastError()).startsWith("NetworkException");
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
        assertThat(first.getLastError()).startsWith("TimeoutException");
        verify(kafka, times(1)).send(any(ProducerRecord.class));
    }

    static Stream<RuntimeException> permanentFailures() {
        return Stream.of(
            new RecordTooLargeException("too large"),
            new SerializationException("cannot serialize"),
            new InvalidTopicException("bad topic"),
            new TopicAuthorizationException(Set.of("evt.cus.customer.created.v1")),
            new IllegalStateException("not a Kafka retriable error"));
    }

    @ParameterizedTest
    @MethodSource("permanentFailures")
    void aPermanentFailureParksTheRowAndTheBatchContinuesWithOtherCustomers(RuntimeException permanent) {
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

    @Test
    void aRetryableFailureThatReachesTheAttemptCapParksTheRowAndTheBatchContinues() {
        OutboxEventJpaEntity stuck = row("CUST-1");
        OutboxEventJpaEntity next = row("CUST-2");
        stuck.markFailed("NetworkException");
        stuck.markFailed("NetworkException");
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(stuck, next));
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.failedFuture(producerFailure(new NetworkException("broker down"))))
            .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));

        assertThat(relay.relayOnce()).isEqualTo(1);

        assertThat(stuck.getAttempts()).isEqualTo(MAX_ATTEMPTS);
        assertThat(stuck.getParkedAt()).isEqualTo(NOW);
        assertThat(next.getPublishedAt()).isEqualTo(NOW);
    }

    @Test
    void aRetryableFailureBelowTheCapIsNotParked() {
        OutboxEventJpaEntity row = row("CUST-1");
        row.markFailed("NetworkException");
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(row));
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.failedFuture(producerFailure(new NetworkException("broker down"))));

        assertThat(relay.relayOnce()).isZero();

        assertThat(row.getAttempts()).isEqualTo(MAX_ATTEMPTS - 1);
        assertThat(row.getParkedAt()).isNull();
    }

    @Test
    void theAttemptCapMustBePositive() {
        assertThatThrownBy(() -> new OutboxRelay(outbox, kafka, transactions,
                Clock.fixed(NOW, ZoneOffset.UTC), 50, Duration.ofSeconds(1), Duration.ofDays(7), 0))
            .isInstanceOf(IllegalArgumentException.class);
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
