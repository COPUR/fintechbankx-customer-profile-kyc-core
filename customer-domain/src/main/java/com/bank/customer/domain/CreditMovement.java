package com.bank.customer.domain;

import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One reserve or release of a customer's credit, keyed by the caller's
 * idempotency key so a retried request is applied once. The optional
 * reference names what the credit was moved for (for example a loan id), so
 * reservations can be released and reconciled per loan. The position the
 * movement left behind (limit, used and available credit, in the movement's
 * currency) is kept so a replay answers exactly as the original call did;
 * movements journalled before that was recorded (Flyway V12) carry none.
 */
public record CreditMovement(
    UUID movementId,
    CustomerId customerId,
    String idempotencyKey,
    Type type,
    Money amount,
    String reference,
    Instant occurredAt,
    CreditProfile positionAfter
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
        if (positionAfter != null && !positionAfter.getCreditLimit().getCurrency().equals(amount.getCurrency())) {
            throw new IllegalArgumentException("The position left behind must be in the movement's currency");
        }
    }

    /** A movement whose position is unknown: journalled before V12 recorded it. */
    public CreditMovement(UUID movementId, CustomerId customerId, String idempotencyKey, Type type, Money amount,
                          String reference, Instant occurredAt) {
        this(movementId, customerId, idempotencyKey, type, amount, reference, occurredAt, null);
    }

    /** The position this movement left behind, if it was recorded. */
    public Optional<CreditProfile> position() {
        return Optional.ofNullable(positionAfter);
    }

    /**
     * Whether a request with this movement's idempotency key is a replay of it:
     * same type, amount (and so currency) and reference. Anything else reusing
     * the key is a conflict, never a silent replay.
     */
    public boolean sameInstruction(Type otherType, Money otherAmount, String otherReference) {
        return type == otherType && amount.equals(otherAmount) && Objects.equals(reference, otherReference);
    }
}
