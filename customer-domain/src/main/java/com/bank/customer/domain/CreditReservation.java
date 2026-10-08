package com.bank.customer.domain;

import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;

import java.util.Currency;
import java.util.Objects;

/**
 * Credit reserved for one purpose, named by the caller's reference (the loan
 * id when the loan service calls): the total reserved under that reference
 * and the total released from it. A release that names the reference takes
 * at most {@link #remaining()}. Reserving again under the same reference (a
 * loan's next attempt after a cancelled one) adds to the same reservation.
 * Immutable; each change returns a new value.
 */
public record CreditReservation(CustomerId customerId, String reference, Money reserved, Money released) {

    public CreditReservation {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(reserved, "reserved");
        Objects.requireNonNull(released, "released");
        if (reference == null || reference.isBlank() || reference.length() > CreditMovement.MAX_REFERENCE_LENGTH) {
            throw new IllegalArgumentException("Reference must be 1 to " + CreditMovement.MAX_REFERENCE_LENGTH + " characters");
        }
        if (!reserved.getCurrency().equals(released.getCurrency())) {
            throw new IllegalArgumentException("Reserved and released amounts must be in one currency");
        }
        if (reserved.isNegative() || released.isNegative()) {
            throw new IllegalArgumentException("Reserved and released amounts cannot be negative");
        }
        if (released.compareTo(reserved) > 0) {
            throw new IllegalArgumentException("Released cannot exceed reserved");
        }
    }

    /** No credit reserved under this reference yet. */
    public static CreditReservation none(CustomerId customerId, String reference, Currency currency) {
        return new CreditReservation(customerId, reference, Money.zero(currency), Money.zero(currency));
    }

    public Currency currency() {
        return reserved.getCurrency();
    }

    /** What is still reserved: reserved minus released. */
    public Money remaining() {
        return reserved.subtract(released);
    }

    public CreditReservation reserve(Money amount) {
        requirePositiveInCurrency(amount);
        return new CreditReservation(customerId, reference, reserved.add(amount), released);
    }

    /**
     * @throws ReleaseExceedsReservationException if the amount is more than {@link #remaining()}
     */
    public CreditReservation release(Money amount) {
        requirePositiveInCurrency(amount);
        if (amount.compareTo(remaining()) > 0) {
            throw new ReleaseExceedsReservationException(reference, amount, remaining());
        }
        return new CreditReservation(customerId, reference, reserved, released.add(amount));
    }

    private void requirePositiveInCurrency(Money amount) {
        Objects.requireNonNull(amount, "amount");
        if (!amount.getCurrency().equals(currency())) {
            throw new CreditCurrencyMismatchException(currency(), amount.getCurrency());
        }
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Credit amount must be positive");
        }
    }
}
