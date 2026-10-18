package com.bank.customer.infrastructure.outbox;

import java.time.Duration;
import java.time.Instant;

/**
 * How long the relay waits after a stopped batch (ADR-021 decision 4: stop,
 * retry with backoff, alert). The first wait is the poll interval; each
 * further stopped batch doubles it up to {@code max}; a completed batch
 * resets it. Held per relay instance; only the replica holding the relay
 * lock ever stops a batch.
 */
public final class RelayBackoff {

    private final Duration initial;
    private final Duration max;
    private Duration currentDelay;
    private Instant nextAttemptAt;

    public RelayBackoff(Duration initial, Duration max) {
        if (initial == null || initial.isNegative() || initial.isZero()) {
            throw new IllegalArgumentException("customer.outbox.relay.interval must be positive");
        }
        if (max == null || max.compareTo(initial) < 0) {
            throw new IllegalArgumentException("customer.outbox.relay.backoff-max must be at least the poll interval");
        }
        this.initial = initial;
        this.max = max;
    }

    public Duration initial() {
        return initial;
    }

    public Duration max() {
        return max;
    }

    /** When the relay may try again; null while no wait is due. */
    public synchronized Instant nextAttemptAt() {
        return nextAttemptAt;
    }

    synchronized boolean ready(Instant now) {
        return nextAttemptAt == null || !now.isBefore(nextAttemptAt);
    }

    synchronized void batchStopped(Instant now) {
        currentDelay = currentDelay == null ? initial : min(currentDelay.multipliedBy(2), max);
        nextAttemptAt = now.plus(currentDelay);
    }

    synchronized void batchCompleted() {
        currentDelay = null;
        nextAttemptAt = null;
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
