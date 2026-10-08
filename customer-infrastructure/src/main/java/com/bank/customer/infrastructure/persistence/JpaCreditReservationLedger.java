package com.bank.customer.infrastructure.persistence;

import com.bank.customer.domain.CreditReservation;
import com.bank.customer.domain.port.out.CreditReservationLedger;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

/**
 * Out-port adapter for {@link CreditReservationLedger}. Saves join the
 * caller's transaction (MANDATORY), which has already saved the customer row
 * with its optimistic version check, so two racing releases of one
 * reservation cannot both commit.
 */
@Repository
public class JpaCreditReservationLedger implements CreditReservationLedger {

    private final SpringDataCreditReservationRepository reservations;
    private final Clock clock;

    public JpaCreditReservationLedger(SpringDataCreditReservationRepository reservations,
                                      org.springframework.beans.factory.ObjectProvider<Clock> clock) {
        this.reservations = reservations;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CreditReservation> find(CustomerId customerId, String reference) {
        return reservations.findByCustomerIdAndReference(customerId.getValue(), reference)
            .map(JpaCreditReservationLedger::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Money openAmount(CustomerId customerId, Currency currency) {
        return Money.of(reservations.sumOpen(customerId.getValue(), currency.getCurrencyCode()), currency);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void save(CreditReservation reservation) {
        Instant now = clock.instant();
        CreditReservationJpaEntity row = reservations
            .findByCustomerIdAndReference(reservation.customerId().getValue(), reservation.reference())
            .orElseGet(() -> new CreditReservationJpaEntity(UUID.randomUUID(), reservation.customerId().getValue(),
                reservation.reference(), reservation.currency().getCurrencyCode(), now));
        row.setAmounts(reservation.reserved().getAmount(), reservation.released().getAmount(), now);
        reservations.saveAndFlush(row);
    }

    static CreditReservation toDomain(CreditReservationJpaEntity row) {
        Currency currency = Currency.getInstance(row.getCurrency());
        return new CreditReservation(CustomerId.of(row.getCustomerId()), row.getReference(),
            Money.of(row.getReservedAmount(), currency), Money.of(row.getReleasedAmount(), currency));
    }
}
