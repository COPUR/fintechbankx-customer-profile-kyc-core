package com.bank.customer.domain;

import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Credit reserved under one reference (the loan id): what was reserved, what
 * was released, and what is still open. A release names the reference and
 * takes at most what is still open.
 */
@Tag("unit")
class CreditReservationTest {

    private static final CustomerId CUSTOMER = CustomerId.of("CUST-RES-1");
    private static final Currency AED = Currency.getInstance("AED");

    @Test
    void aNewReferenceHasNothingReservedOrOpen() {
        CreditReservation none = CreditReservation.none(CUSTOMER, "LOAN-77", AED);

        assertThat(none.reserved()).isEqualTo(aed("0.00"));
        assertThat(none.released()).isEqualTo(aed("0.00"));
        assertThat(none.remaining()).isEqualTo(aed("0.00"));
        assertThat(none.customerId()).isEqualTo(CUSTOMER);
        assertThat(none.reference()).isEqualTo("LOAN-77");
    }

    @Test
    void partialReleasesTakeFromWhatIsStillOpen() {
        CreditReservation reserved = CreditReservation.none(CUSTOMER, "LOAN-77", AED).reserve(aed("2500.00"));

        CreditReservation afterFirst = reserved.release(aed("1000.00"));
        CreditReservation afterSecond = afterFirst.release(aed("1500.00"));

        assertThat(reserved.remaining()).isEqualTo(aed("2500.00"));
        assertThat(afterFirst.remaining()).isEqualTo(aed("1500.00"));
        assertThat(afterSecond.remaining()).isEqualTo(aed("0.00"));
        assertThat(afterSecond.reserved()).isEqualTo(aed("2500.00"));
        assertThat(afterSecond.released()).isEqualTo(aed("2500.00"));
    }

    @Test
    void aReleaseAboveWhatIsOpenIsRefusedAndChangesNothing() {
        CreditReservation reservation = CreditReservation.none(CUSTOMER, "LOAN-77", AED)
            .reserve(aed("2500.00"))
            .release(aed("1000.00"));

        assertThatThrownBy(() -> reservation.release(aed("1500.01")))
            .isInstanceOf(ReleaseExceedsReservationException.class)
            .hasMessageContaining("LOAN-77");
        assertThat(reservation.remaining()).isEqualTo(aed("1500.00"));
    }

    /** The loan reserves again under the same loan id after a cancelled attempt (key :g1). */
    @Test
    void aSecondReserveUnderTheSameReferenceAddsToIt() {
        CreditReservation cancelled = CreditReservation.none(CUSTOMER, "LOAN-77", AED)
            .reserve(aed("2500.00"))
            .release(aed("2500.00"));

        CreditReservation again = cancelled.reserve(aed("2500.00"));

        assertThat(again.reserved()).isEqualTo(aed("5000.00"));
        assertThat(again.released()).isEqualTo(aed("2500.00"));
        assertThat(again.remaining()).isEqualTo(aed("2500.00"));
    }

    @Test
    void theReferenceAndFiguresAreValidated() {
        assertThatThrownBy(() -> CreditReservation.none(CUSTOMER, " ", AED))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CreditReservation.none(CUSTOMER, "L".repeat(129), AED))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CreditReservation(CUSTOMER, "LOAN-1", aed("100.00"), aed("100.01")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CreditReservation(CUSTOMER, "LOAN-1", aed("100.00"), Money.usd(BigDecimal.ZERO)))
            .isInstanceOf(IllegalArgumentException.class);
        CreditReservation reservation = CreditReservation.none(CUSTOMER, "LOAN-1", AED);
        assertThatThrownBy(() -> reservation.reserve(aed("0.00"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reservation.reserve(Money.usd(new BigDecimal("1.00"))))
            .isInstanceOf(CreditCurrencyMismatchException.class);
    }

    private static Money aed(String amount) {
        return Money.aed(new BigDecimal(amount));
    }
}
