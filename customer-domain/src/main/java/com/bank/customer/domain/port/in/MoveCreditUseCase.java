package com.bank.customer.domain.port.in;

/**
 * Reserves and releases a customer's credit, each applied once per
 * idempotency key. A retry with the same key and instruction returns the
 * current position; the same key with a different instruction is refused with
 * {@link com.bank.customer.domain.IdempotencyKeyConflictException}.
 */
public interface MoveCreditUseCase {

    /** @throws com.bank.customer.domain.InsufficientCreditException if the available credit is too low */
    CustomerProfile reserveCredit(CreditMovementCommand command);

    CustomerProfile releaseCredit(CreditMovementCommand command);
}
