package com.bank.customer.domain;

import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One reserve or release of a customer's credit, keyed by the caller's
 * idempotency key so a retried request is applied once.
 */
public record CreditMovement(
    UUID movementId,
    CustomerId customerId,
    String idempotencyKey,
    Type type,
    Money amount,
    Instant occurredAt
) {

    public static final int MAX_KEY_LENGTH = 128;

    public enum Type { RESERVE, RELEASE }

    public CreditMovement {
        Objects.requireNonNull(movementId, "movementId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("Idempotency key must be 1 to " + MAX_KEY_LENGTH + " characters");
        }
    }

    public boolean sameInstruction(Type otherType, Money otherAmount) {
        return type == otherType && amount.equals(otherAmount);
    }
}
