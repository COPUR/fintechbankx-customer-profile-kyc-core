package com.bank.customer.application;

/**
 * Exception thrown when an idempotency key is reused for a different credit
 * movement than the one it was first used for.
 */
public class IdempotencyKeyConflictException extends RuntimeException {

    public IdempotencyKeyConflictException(String message) {
        super(message);
    }

    public static IdempotencyKeyConflictException forKey(String idempotencyKey) {
        return new IdempotencyKeyConflictException(
            "Idempotency key was already used for a different credit movement: " + idempotencyKey);
    }
}
