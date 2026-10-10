package com.bank.customer.domain.port.out;

import com.bank.customer.domain.CreditReservation;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;

import java.util.Currency;
import java.util.Optional;

/**
 * Credit reservations per customer and reference. Writes join the caller's
 * transaction after the customer is saved, so the customer's optimistic
 * version guards the reservations too: every reservation change also changes
 * the customer's used credit.
 */
public interface CreditReservationLedger {

    Optional<CreditReservation> find(CustomerId customerId, String reference);

    /** Sum of what is still reserved over all the customer's reservations; zero if none. */
    Money openAmount(CustomerId customerId, Currency currency);

    /** Inserts the reservation, or updates the one with the same customer and reference. */
    void save(CreditReservation reservation);
}
