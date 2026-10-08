package com.bank.customer.domain;

import com.bank.shared.kernel.domain.Money;

/**
 * Domain exception: a release names no known reservation and asks for more
 * than the customer's untracked used credit (used credit minus every open
 * reservation). Such a release may free balances migrated from the monolith
 * or reserved without a reference, never credit another reservation holds.
 * The figures are for the log; the API answers without them.
 */
public class ReservationNotFoundException extends RuntimeException {

    public ReservationNotFoundException(Money requested, Money untracked) {
        super("No reservation matches the release, and " + requested + " exceeds the " + untracked
            + " of used credit no reservation accounts for");
    }
}
