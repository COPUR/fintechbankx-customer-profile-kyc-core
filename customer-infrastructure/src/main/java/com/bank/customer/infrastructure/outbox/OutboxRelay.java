package com.bank.customer.infrastructure.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaProducerException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Relays committed outbox rows to Kafka in insertion order.
 *
 * One replica relays at a time (Postgres advisory lock), so the service can
 * scale out without reordering an aggregate's events. Consumers de-duplicate
 * on eventId, which makes the at-least-once delivery safe.
 *
 * A failed send is handled by what failed (ADR-021 decision 4):
 * <ul>
 *   <li>payload failures (RecordTooLarge, Serialization, InvalidTopic): the
 *   record itself can never be sent, so the row is parked (parked_at set,
 *   reason in last_error, counted once by outbox.parked.events) and the batch
 *   continues;</li>
 *   <li>everything else (Kafka retriable errors and the relay's send timeout,
 *   SASL, authentication and authorization errors, a generic KafkaException,
 *   any other exception): the batch stops without marking the row or
 *   anything after it, and the row is retried after the backoff. Such a row
 *   is never parked or skipped by the relay, however long it fails, so a
 *   customer's events stay complete and in order.</li>
 * </ul>
 * The parked customer's later events wait behind it (here and in the batch
 * query); other customers' events flow. Parked rows are replayed by hand, and
 * an operator may park a row stuck on a non-payload error by hand (runbook
 * "Parked outbox events").
 *
 * After a stopped batch the relay backs off ({@link RelayBackoff}): it waits
 * the poll interval, doubling per stopped batch up to backoff-max, and resets
 * after a completed batch. Every failed send increments
 * outbox.send.failures tagged with the exception's simple class name; the
 * alert signal is outbox.oldest.pending.age.seconds (ADR-021 decision 4).
 */
public class OutboxRelay {

    static final String OPERATOR_PARK = "OperatorPark";
    static final long RELAY_LOCK_KEY = 0x6375735F6F7574L; // "cus_out"
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final SpringDataOutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final int batchSize;
    private final Duration sendTimeout;
    private final Duration retention;
    private final RelayBackoff backoff;
    private final MeterRegistry meters;

    public OutboxRelay(SpringDataOutboxRepository outbox, KafkaTemplate<String, String> kafka,
                       TransactionTemplate transactions, Clock clock, int batchSize,
                       Duration sendTimeout, Duration retention,
                       Duration pollInterval, Duration backoffMax, MeterRegistry meters) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.transactions = transactions;
        this.clock = clock;
        this.batchSize = batchSize;
        this.sendTimeout = sendTimeout;
        this.retention = retention;
        this.backoff = new RelayBackoff(pollInterval, backoffMax);
        this.meters = meters;
    }

    public RelayBackoff backoff() {
        return backoff;
    }

    /**
     * @return number of events published in this run
     */
    public int relayOnce() {
        if (!backoff.ready(clock.instant())) {
            return 0;
        }
        boolean[] stopped = {false};
        boolean[] ran = {false};
        Integer published = transactions.execute(status -> {
            if (!outbox.tryRelayLock(RELAY_LOCK_KEY)) {
                return 0;
            }
            ran[0] = true;
            countOperatorParks();
            List<OutboxEventJpaEntity> batch = outbox.findUnpublishedBatch(batchSize);
            int sent = 0;
            Set<String> heldAggregates = new HashSet<>();
            for (OutboxEventJpaEntity row : batch) {
                if (heldAggregates.contains(aggregateKey(row))) {
                    continue;
                }
                try {
                    kafka.send(toRecord(row)).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
                    row.markPublished(clock.instant());
                    sent++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    countFailure(e);
                    stopped[0] = true;
                    break;
                } catch (Exception e) {
                    countFailure(e);
                    if (!isPayloadFailure(e)) {
                        log.warn("Outbox relay could not publish event {} to {}: {}; batch stopped, will retry after backoff",
                            row.getEventId(), row.getTopic(), describe(e), e);
                        stopped[0] = true;
                        break;
                    }
                    Instant now = clock.instant();
                    row.markFailed(describe(e), now);
                    row.park(now);
                    countParked(rootClass(e).getSimpleName());
                    heldAggregates.add(aggregateKey(row));
                    log.error("Outbox relay parked event {} for {} after {} attempt(s): {}; replay it by hand",
                        row.getEventId(), row.getTopic(), row.getAttempts(), row.getLastError(), e);
                }
            }
            return sent;
        });
        if (stopped[0]) {
            backoff.batchStopped(clock.instant());
        } else if (ran[0]) {
            backoff.batchCompleted();
        }
        return published == null ? 0 : published;
    }

    /**
     * Rows an operator parked by hand are counted once, as OperatorPark, and
     * marked counted in the same transaction. Only the replica holding the
     * relay lock gets here, so no row is counted twice.
     */
    private void countOperatorParks() {
        int operatorParks = outbox.markOperatorParksCounted();
        for (int i = 0; i < operatorParks; i++) {
            countParked(OPERATOR_PARK);
        }
    }

    /** outbox.parked.events (Prometheus outbox_parked_events_total), once per parked row. */
    private void countParked(String exception) {
        Counter.builder("outbox.parked.events")
            .description("Outbox rows parked, by root cause (OperatorPark for a manual park)")
            .tag("exception", exception)
            .register(meters)
            .increment();
    }

    private void countFailure(Throwable failure) {
        Counter.builder("outbox.send.failures")
            .description("Failed outbox sends to Kafka, by exception class")
            .tag("exception", rootClass(failure).getSimpleName())
            .register(meters)
            .increment();
    }

    public int purgePublished() {
        Integer deleted = transactions.execute(status -> outbox.deletePublishedBefore(clock.instant().minus(retention)));
        return deleted == null ? 0 : deleted;
    }

    private static String aggregateKey(OutboxEventJpaEntity row) {
        return row.getAggregateType() + "/" + row.getAggregateId();
    }

    /**
     * Payload failure: the record itself cannot be sent (too large, not
     * serializable, invalid topic name), so retrying cannot help. Any other
     * failure stops the batch and is retried after the backoff; never parked
     * by the relay (ADR-021 decision 4).
     */
    static boolean isPayloadFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof RecordTooLargeException || cause instanceof SerializationException
                    || cause instanceof InvalidTopicException) {
                return true;
            }
        }
        return false;
    }

    /** The underlying failure, without the future and KafkaTemplate wrappers, for last_error. */
    static String describe(Throwable failure) {
        Throwable cause = unwrap(failure);
        return cause.getMessage() == null
            ? cause.getClass().getSimpleName()
            : cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    private static Class<?> rootClass(Throwable failure) {
        return unwrap(failure).getClass();
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof ExecutionException || cause instanceof CompletionException
                || cause instanceof KafkaProducerException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    /**
     * The record for one outbox row (ADR-019 s3): the row's topic (the
     * aggregate topic evt.cus.customer.v1), key = aggregateId, value = the
     * envelope, and the UTF-8 headers eventType, eventId and correlationId,
     * equal to the envelope, so consumers route on eventType without parsing
     * the value and skip types they do not handle; traceparent when the
     * request carried one. x-fapi-interaction-id repeats the correlation id,
     * which CorrelationIdFilter takes from that request header when present.
     */
    static ProducerRecord<String, String> toRecord(OutboxEventJpaEntity row) {
        ProducerRecord<String, String> record = new ProducerRecord<>(row.getTopic(), row.getAggregateId(), row.getPayload());
        record.headers().add("eventType", row.getEventType().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventId", row.getEventId().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("correlationId", row.getCorrelationId().getBytes(StandardCharsets.UTF_8));
        record.headers().add("x-fapi-interaction-id", row.getCorrelationId().getBytes(StandardCharsets.UTF_8));
        if (row.getTraceparent() != null) {
            record.headers().add("traceparent", row.getTraceparent().getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }
}
