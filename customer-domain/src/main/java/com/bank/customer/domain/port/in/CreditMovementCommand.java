package com.bank.customer.domain.port.in;

import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;

import java.util.Objects;

/**
 * Reserve or release an amount of a customer's credit. The idempotency key is
 * required, so a retried call never moves credit twice; reference names what
 * the credit is moved for (for example the loan id) and may be null.
 */
public record CreditMovementCommand(
    CustomerId customerId,
    Money amount,
    String idempotencyKey,
    String reference
) {

    public CreditMovementCommand {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(amount, "amount");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency key is required");
        }
    }
}
