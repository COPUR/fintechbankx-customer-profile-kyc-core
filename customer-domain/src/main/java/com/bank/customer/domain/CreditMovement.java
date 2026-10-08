package com.bank.customer.domain;

import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One reserve or release of a customer's credit, keyed by the caller's
 * idempotency key so a retried request is applied once. The optional
 * reference names what the credit was moved for (for example a loan id), so
 * reservations can be released and reconciled per loan.
 */
public record CreditMovement(
    UUID movementId,
    CustomerId customerId,
    String idempotencyKey,
    Type type,
    Money amount,
    String reference,
    Instant occurredAt
) {

    public static final int MAX_KEY_LENGTH = 128;
    public static final int MAX_REFERENCE_LENGTH = 128;

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
        if (reference != null && (reference.isBlank() || reference.length() > MAX_REFERENCE_LENGTH)) {
            throw new IllegalArgumentException("Reference must be 1 to " + MAX_REFERENCE_LENGTH + " characters");
        }
    }

    public boolean sameInstruction(Type otherType, Money otherAmount) {
        return type == otherType && amount.equals(otherAmount);
    }
}
