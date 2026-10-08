package com.bank.customer.infrastructure.outbox;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RelayBackoffTest {

    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");

    @Test
    void eachStoppedBatchDoublesTheWaitFromThePollIntervalUpToTheCap() {
        RelayBackoff backoff = new RelayBackoff(Duration.ofSeconds(1), Duration.ofMinutes(5));
        Instant at = NOW;
        long[] expectedSeconds = {1, 2, 4, 8, 16, 32, 64, 128, 256, 300, 300};

        for (long seconds : expectedSeconds) {
            backoff.batchStopped(at);
            assertThat(backoff.nextAttemptAt()).isEqualTo(at.plusSeconds(seconds));
            assertThat(backoff.ready(at.plusSeconds(seconds).minusMillis(1))).as("waits %ss", seconds).isFalse();
            assertThat(backoff.ready(at.plusSeconds(seconds))).isTrue();
            at = at.plusSeconds(seconds);
        }
    }

    @Test
    void aBatchThatCompletesResetsTheWait() {
        RelayBackoff backoff = new RelayBackoff(Duration.ofSeconds(1), Duration.ofMinutes(5));
        backoff.batchStopped(NOW);
        backoff.batchStopped(NOW.plusSeconds(1));
        backoff.batchStopped(NOW.plusSeconds(3));

        backoff.batchCompleted();

        assertThat(backoff.ready(NOW.plusSeconds(3))).as("no wait after a completed batch").isTrue();
        backoff.batchStopped(NOW.plusSeconds(10));
        assertThat(backoff.nextAttemptAt()).as("starts again from the poll interval").isEqualTo(NOW.plusSeconds(11));
    }

    @Test
    void aFreshBackoffDoesNotWait() {
        RelayBackoff backoff = new RelayBackoff(Duration.ofSeconds(1), Duration.ofMinutes(5));

        assertThat(backoff.ready(NOW)).isTrue();
        assertThat(backoff.nextAttemptAt()).isNull();
    }

    @Test
    void theIntervalAndCapMustBePositiveAndOrdered() {
        assertThatThrownBy(() -> new RelayBackoff(Duration.ZERO, Duration.ofMinutes(5)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RelayBackoff(Duration.ofMinutes(10), Duration.ofMinutes(5)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
