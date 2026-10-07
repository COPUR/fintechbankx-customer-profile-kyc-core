package com.bank.customer.infrastructure.persistence;

import com.bank.customer.domain.Customer;
import com.bank.customer.domain.CustomerSnapshot;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;

import java.math.BigDecimal;
import java.util.Currency;

/**
 * Maps between the Customer aggregate and its row. Reads go through
 * {@link Customer#rehydrate}, so loading a customer never raises events or
 * re-runs creation rules.
 */
final class CustomerPersistenceMapper {

    private CustomerPersistenceMapper() {
    }

    static Customer toDomain(CustomerJpaEntity row) {
        Currency currency = Currency.getInstance(row.getCurrency());
        return Customer.rehydrate(new CustomerSnapshot(
            CustomerId.of(row.getCustomerId()),
            row.getFirstName(),
            row.getLastName(),
            row.getEmail(),
            row.getPhoneNumber(),
            Money.of(row.getCreditLimit(), currency),
            Money.of(row.getUsedCredit(), currency),
            row.getCreditScore(),
            row.getMonthlyIncome() == null ? null : Money.of(row.getMonthlyIncome(), currency),
            row.getCreatedAt(),
            row.getUpdatedAt(),
            row.getVersion()));
    }

    static CustomerJpaEntity newEntity(Customer customer) {
        CustomerJpaEntity row = new CustomerJpaEntity(customer.getId().getValue());
        copyInto(customer, row);
        row.setCreatedAt(customer.getCreatedAt());
        return row;
    }

    static void copyInto(Customer customer, CustomerJpaEntity row) {
        Money limit = customer.getCreditProfile().getCreditLimit();
        Currency currency = limit.getCurrency();
        row.setFirstName(customer.getFirstName());
        row.setLastName(customer.getLastName());
        row.setEmail(customer.getEmail());
        row.setPhoneNumber(customer.getPhoneNumber());
        row.setCurrency(currency.getCurrencyCode());
        row.setCreditLimit(limit.getAmount());
        row.setUsedCredit(amountIn(customer.getCreditProfile().getUsedCredit(), currency));
        row.setCreditScore(customer.getCreditScore());
        row.setMonthlyIncome(customer.getMonthlyIncome() == null ? null : amountIn(customer.getMonthlyIncome(), currency));
        row.setUpdatedAt(customer.getUpdatedAt());
    }

    private static BigDecimal amountIn(Money money, Currency currency) {
        if (!money.getCurrency().equals(currency)) {
            throw new IllegalStateException("Customer amounts must share the credit limit currency " + currency
                + " but got " + money.getCurrency());
        }
        return money.getAmount();
    }
}
