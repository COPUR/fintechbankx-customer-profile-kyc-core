package com.bank.customer.infrastructure.persistence;

import com.bank.customer.domain.CreditMovement;
import com.bank.customer.domain.port.out.CreditMovementJournal;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Currency;
import java.util.Optional;

/**
 * Out-port adapter for {@link CreditMovementJournal}. Records join the
 * caller's transaction, so a movement is journalled only if the customer's
 * credit change commits. Two concurrent first calls with one key are stopped
 * by the unique (customer_id, idempotency_key) index.
 */
@Repository
public class JpaCreditMovementJournal implements CreditMovementJournal {

    private final SpringDataCreditMovementRepository movements;

    public JpaCreditMovementJournal(SpringDataCreditMovementRepository movements) {
        this.movements = movements;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CreditMovement> find(CustomerId customerId, String idempotencyKey) {
        return movements.findByCustomerIdAndIdempotencyKey(customerId.getValue(), idempotencyKey)
            .map(JpaCreditMovementJournal::toDomain);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(CreditMovement movement) {
        movements.saveAndFlush(new CreditMovementJpaEntity(
            movement.movementId(),
            movement.customerId().getValue(),
            movement.idempotencyKey(),
            movement.type().name(),
            movement.amount().getCurrency().getCurrencyCode(),
            movement.amount().getAmount(),
            movement.reference(),
            movement.occurredAt()));
    }

    static CreditMovement toDomain(CreditMovementJpaEntity row) {
        return new CreditMovement(
            row.getMovementId(),
            CustomerId.of(row.getCustomerId()),
            row.getIdempotencyKey(),
            CreditMovement.Type.valueOf(row.getMovementType()),
            Money.of(row.getAmount(), Currency.getInstance(row.getCurrency())),
            row.getReference(),
            row.getOccurredAt());
    }
}
