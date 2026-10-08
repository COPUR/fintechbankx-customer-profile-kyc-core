package com.bank.customer.domain;

import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Release by reference (loan PR #14 review): a release that names a
 * reservation takes at most that reservation's open amount; a release that
 * names none takes at most the untracked used credit (used credit minus all
 * open reservations), which covers balances migrated from the monolith
 * without eating into another loan's reservation. Nothing floors at zero any more.
 */
@Tag("unit")
class CustomerCreditReleaseTest {

    private static final Currency AED = Currency.getInstance("AED");

    @Test
    void aReserveUnderAReferenceIsTrackedOnThatReservation() {
        Customer customer = customer("10000.00");

        CreditReservation reservation = customer.reserveCredit(aed("3000.00"), none(customer, "LOAN-1"));

        assertThat(customer.getCreditProfile().getUsedCredit()).isEqualTo(aed("3000.00"));
        assertThat(reservation.remaining()).isEqualTo(aed("3000.00"));
        assertThat(customer.getDomainEvents()).singleElement().isInstanceOf(CustomerCreditReservedEvent.class);
    }

    @Test
    void aReleaseNamingAReservationTakesPartOfItAndRaisesTheEvent() {
        Customer customer = customer("10000.00");
        CreditReservation reservation = customer.reserveCredit(aed("3000.00"), none(customer, "LOAN-1"));
        customer.clearDomainEvents();

        CreditReservation after = customer.releaseCredit(aed("1000.00"), reservation);

        assertThat(customer.getCreditProfile().getUsedCredit()).isEqualTo(aed("2000.00"));
        assertThat(after.remaining()).isEqualTo(aed("2000.00"));
        assertThat(customer.getDomainEvents()).singleElement()
            .isInstanceOfSatisfying(CustomerCreditReleasedEvent.class,
                event -> assertThat(event.getReleasedAmount()).isEqualTo(aed("1000.00")));
    }

    @Test
    void aReleaseAboveTheReservationIsRefusedEvenWhenUsedCreditWouldCoverIt() {
        Customer customer = customer("10000.00");
        customer.reserveCredit(aed("4000.00"));                     // untracked
        CreditReservation loan1 = customer.reserveCredit(aed("3000.00"), none(customer, "LOAN-1"));
        CreditReservation partly = customer.releaseCredit(aed("1000.00"), loan1);
        customer.clearDomainEvents();

        assertThatThrownBy(() -> customer.releaseCredit(aed("2000.01"), partly))
            .isInstanceOf(ReleaseExceedsReservationException.class);

        assertThat(customer.getCreditProfile().getUsedCredit()).isEqualTo(aed("6000.00"));
        assertThat(customer.getDomainEvents()).isEmpty();
    }

    @Test
    void aReleaseNamingNoReservationTakesOnlyUntrackedCredit() {
        Customer customer = customer("10000.00");
        customer.reserveCredit(aed("2000.00"));                     // untracked, e.g. migrated balance
        customer.reserveCredit(aed("3000.00"), none(customer, "LOAN-1"));
        Money openReservations = aed("3000.00");
        customer.clearDomainEvents();

        assertThatThrownBy(() -> customer.releaseUntrackedCredit(aed("2000.01"), openReservations))
            .isInstanceOf(ReservationNotFoundException.class);
        assertThat(customer.getCreditProfile().getUsedCredit()).isEqualTo(aed("5000.00"));
        assertThat(customer.getDomainEvents()).isEmpty();

        customer.releaseUntrackedCredit(aed("2000.00"), openReservations);

        assertThat(customer.getCreditProfile().getUsedCredit()).isEqualTo(aed("3000.00"));
        assertThat(customer.getDomainEvents()).singleElement().isInstanceOf(CustomerCreditReleasedEvent.class);
    }

    /** Parity CU-09 changes on purpose: a release beyond used credit no longer floors it at zero. */
    @Test
    void aMigratedBalanceCanBeReleasedButNotBeyondUsedCredit() {
        Customer migrated = Customer.rehydrate(new CustomerSnapshot(CustomerId.of("42"), "Amina", "Haddad", null, null,
            aed("50000.00"), aed("500.00"), null, null,
            LocalDateTime.of(2024, 3, 1, 9, 0), LocalDateTime.of(2025, 1, 2, 10, 30), 7L));
        Money noReservations = aed("0.00");

        assertThatThrownBy(() -> migrated.releaseUntrackedCredit(aed("1000.00"), noReservations))
            .isInstanceOf(ReservationNotFoundException.class);
        assertThat(migrated.getCreditProfile().getUsedCredit()).isEqualTo(aed("500.00"));

        migrated.releaseUntrackedCredit(aed("500.00"), noReservations);
        assertThat(migrated.getCreditProfile().getUsedCredit()).isEqualTo(aed("0.00"));
    }

    @Test
    void currencyAndAmountRulesComeBeforeTheReservationRules() {
        Customer customer = customer("10000.00");
        CreditReservation reservation = customer.reserveCredit(aed("3000.00"), none(customer, "LOAN-1"));
        customer.clearDomainEvents();

        assertThatThrownBy(() -> customer.releaseCredit(Money.usd(new BigDecimal("10.00")), reservation))
            .isInstanceOf(CreditCurrencyMismatchException.class);
        assertThatThrownBy(() -> customer.releaseUntrackedCredit(Money.usd(new BigDecimal("10.00")), aed("0.00")))
            .isInstanceOf(CreditCurrencyMismatchException.class);
        assertThatThrownBy(() -> customer.releaseCredit(aed("0.00"), reservation))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Credit amount must be positive");
        assertThatThrownBy(() -> customer.releaseUntrackedCredit(aed("-1.00"), aed("0.00")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Credit amount must be positive");
        assertThat(customer.getDomainEvents()).isEmpty();
    }

    @Test
    void anotherCustomersReservationIsRefused() {
        Customer customer = customer("10000.00");
        CreditReservation foreign = CreditReservation.none(CustomerId.of("CUST-OTHER"), "LOAN-1", AED)
            .reserve(aed("100.00"));

        assertThatThrownBy(() -> customer.releaseCredit(aed("100.00"), foreign))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> customer.reserveCredit(aed("100.00"), foreign))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(customer.getCreditProfile().getUsedCredit()).isEqualTo(aed("0.00"));
    }

    @Test
    void aReserveRefusedForInsufficientCreditLeavesTheReservationAlone() {
        Customer customer = customer("1000.00");
        CreditReservation none = none(customer, "LOAN-1");

        assertThatThrownBy(() -> customer.reserveCredit(aed("1000.01"), none))
            .isInstanceOf(InsufficientCreditException.class);
        assertThat(none.remaining()).isEqualTo(aed("0.00"));
    }

    private static CreditReservation none(Customer customer, String reference) {
        return CreditReservation.none(customer.getId(), reference, AED);
    }

    private static Customer customer(String limit) {
        Customer customer = Customer.create(CustomerId.of("CUST-REL-1"), "Ali", "Sample", "ali@example.com",
            "+971500000001", aed(limit));
        customer.clearDomainEvents();
        return customer;
    }

    private static Money aed(String amount) {
        return Money.aed(new BigDecimal(amount));
    }
}
