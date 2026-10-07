package com.bank.customer.domain.port.out;

import com.bank.customer.domain.CreditMovement;
import com.bank.shared.kernel.domain.CustomerId;

import java.util.Optional;

/**
 * Record of applied credit reservations and releases, used to make those
 * commands idempotent per customer and key.
 */
public interface CreditMovementJournal {

    Optional<CreditMovement> find(CustomerId customerId, String idempotencyKey);

    void record(CreditMovement movement);
}
