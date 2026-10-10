package com.bank.customer.domain.port.in;

/**
 * Reserves and releases a customer's credit, each applied once per
 * idempotency key. A retry with the same key and instruction returns the
 * current position; the same key with a different instruction is refused with
 * {@link com.bank.customer.domain.IdempotencyKeyConflictException}.
 */
public interface MoveCreditUseCase {

    /**
     * @return the credit position only: callers are services and must not see personal data
     * @throws com.bank.customer.domain.InsufficientCreditException if the available credit is too low
     */
    CreditPosition reserveCredit(CreditMovementCommand command);

    /** @return the credit position only */
    CreditPosition releaseCredit(CreditMovementCommand command);
}
