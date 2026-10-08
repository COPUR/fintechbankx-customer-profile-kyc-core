package com.bank.customer.infrastructure.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.RetriableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaProducerException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
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
 * A failed send is handled by what failed:
 * <ul>
 *   <li>retryable (a Kafka {@link RetriableException}, including the producer's
 *   own timeouts, or the relay's send timeout): the batch stops and the row is
 *   retried on the next run, so later events cannot overtake it;</li>
 *   <li>permanent (RecordTooLarge, Serialization, InvalidTopic,
 *   TopicAuthorization, anything else that is not retriable), or a retryable
 *   failure on the row's {@code maxAttempts}-th attempt: the row is parked
 *   (parked_at set, reason in last_error) and the batch continues. The parked
 *   customer's later events wait behind it (here and in the batch query) so a
 *   customer's events stay in order; other customers' events flow. Parked rows
 *   are counted by the outbox.parked.events gauge and replayed by hand
 *   (runbook "Parked outbox events").</li>
 * </ul>
 */
public class OutboxRelay {

    static final long RELAY_LOCK_KEY = 0x6375735F6F7574L; // "cus_out"
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final SpringDataOutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final int batchSize;
    private final Duration sendTimeout;
    private final Duration retention;
    private final int maxAttempts;

    public OutboxRelay(SpringDataOutboxRepository outbox, KafkaTemplate<String, String> kafka,
                       TransactionTemplate transactions, Clock clock, int batchSize,
                       Duration sendTimeout, Duration retention, int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("customer.outbox.relay.max-attempts must be at least 1");
        }
        this.outbox = outbox;
        this.kafka = kafka;
        this.transactions = transactions;
        this.clock = clock;
        this.batchSize = batchSize;
        this.sendTimeout = sendTimeout;
        this.retention = retention;
        this.maxAttempts = maxAttempts;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /**
     * @return number of events published in this run
     */
    public int relayOnce() {
        Integer published = transactions.execute(status -> {
            if (!outbox.tryRelayLock(RELAY_LOCK_KEY)) {
                return 0;
            }
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
                    row.markFailed("interrupted");
                    break;
                } catch (Exception e) {
                    row.markFailed(describe(e));
                    if (isRetryable(e) && row.getAttempts() < maxAttempts) {
                        log.warn("Outbox relay could not publish event {} to {} (attempt {} of {}); will retry",
                            row.getEventId(), row.getTopic(), row.getAttempts(), maxAttempts, e);
                        break;
                    }
                    row.park(clock.instant());
                    heldAggregates.add(aggregateKey(row));
                    log.error("Outbox relay parked event {} for {} after {} attempt(s): {}; replay it by hand",
                        row.getEventId(), row.getTopic(), row.getAttempts(), row.getLastError(), e);
                }
            }
            return sent;
        });
        return published == null ? 0 : published;
    }

    public int purgePublished() {
        Integer deleted = transactions.execute(status -> outbox.deletePublishedBefore(clock.instant().minus(retention)));
        return deleted == null ? 0 : deleted;
    }

    private static String aggregateKey(OutboxEventJpaEntity row) {
        return row.getAggregateType() + "/" + row.getAggregateId();
    }

    /** Retryable: a Kafka RetriableException (timeouts included) or the relay's own send timeout. */
    static boolean isRetryable(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof RetriableException || cause instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }

    /** The underlying failure, without the future and KafkaTemplate wrappers, for last_error. */
    static String describe(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof ExecutionException || cause instanceof CompletionException
                || cause instanceof KafkaProducerException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null
            ? cause.getClass().getSimpleName()
            : cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

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
