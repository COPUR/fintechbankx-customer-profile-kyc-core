package com.bank.customer.domain;

import com.bank.shared.kernel.domain.Money;

/**
 * Domain exception: a release names a reservation (by its reference) and
 * asks for more than that reservation still holds. Nothing is released.
 * The figures are for the log; the API answers without them.
 */
public class ReleaseExceedsReservationException extends RuntimeException {

    public ReleaseExceedsReservationException(String reference, Money requested, Money remaining) {
        super("Release of " + requested + " exceeds the " + remaining + " still reserved under reference " + reference);
    }
}
