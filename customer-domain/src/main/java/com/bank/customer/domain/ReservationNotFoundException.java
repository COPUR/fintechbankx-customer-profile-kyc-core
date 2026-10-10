package com.bank.customer.domain;

import com.bank.shared.kernel.domain.Money;

/**
 * Domain exception, answered 422 RESERVATION_NOT_FOUND:
 * <ul>
 *   <li>a release carries a reference that matches none of the customer's
 *   reservations. It is always refused, whatever untracked credit exists, so
 *   an unknown loan id can never free migrated or unreferenced credit;</li>
 *   <li>a release without a reference asks for more than the customer's
 *   untracked used credit (used credit minus every open reservation). Such a
 *   release may free balances migrated from the monolith or reserved without
 *   a reference, never credit another reservation holds.</li>
 * </ul>
 * The figures are for the log; the API answers without them.
 */
public class ReservationNotFoundException extends RuntimeException {

    public ReservationNotFoundException(Money requested, Money untracked) {
        super("No reservation matches the release, and " + requested + " exceeds the " + untracked
            + " of used credit no reservation accounts for");
    }

    private ReservationNotFoundException(String message) {
        super(message);
    }

    /** The release names a reference with no reservation; the reference itself stays out of the message. */
    public static ReservationNotFoundException forUnknownReference(Money requested) {
        return new ReservationNotFoundException("The release of " + requested
            + " carries a reference that matches no reservation; only a release without a reference may free untracked credit");
    }
}
