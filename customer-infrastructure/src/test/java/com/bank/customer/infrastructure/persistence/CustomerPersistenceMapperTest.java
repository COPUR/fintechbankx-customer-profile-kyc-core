package com.bank.customer.infrastructure.persistence;

import com.bank.customer.domain.Customer;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerPersistenceMapperTest {

    @Test
    void roundTripKeepsStateAndRaisesNoEvents() {
        Customer original = Customer.createWithCreditScore(CustomerId.of("CUST-MAP-1"), "Omar", "Saeed",
            "omar@example.com", "+971500000011", Money.aed(new BigDecimal("8000.00")), 760);
        original.reserveCredit(Money.aed(new BigDecimal("1500.00")));

        CustomerJpaEntity row = CustomerPersistenceMapper.newEntity(original);
        Customer loaded = CustomerPersistenceMapper.toDomain(row);

        assertThat(row.getCurrency()).isEqualTo("AED");
        assertThat(row.getCreditLimit()).isEqualByComparingTo("40000.00");
        assertThat(row.getCreatedAt()).isEqualTo(original.getCreatedAt());
        assertThat(loaded.getId()).isEqualTo(original.getId());
        assertThat(loaded.getEmail()).isEqualTo("omar@example.com");
        assertThat(loaded.getPhoneNumber()).isEqualTo("+971500000011");
        assertThat(loaded.getCreditScore()).isEqualTo(760);
        assertThat(loaded.getMonthlyIncome()).isEqualTo(Money.aed(new BigDecimal("8000.00")));
        assertThat(loaded.getCreditProfile()).isEqualTo(original.getCreditProfile());
        assertThat(loaded.getDomainEvents()).isEmpty();
    }

    @Test
    void legacyRowWithoutContactOrIncomeLoads() {
        Customer original = Customer.create(CustomerId.of("CUST-MAP-2"), "Sara", "Ali", "sara@example.com", null,
            Money.usd(new BigDecimal("2500.00")));
        CustomerJpaEntity row = CustomerPersistenceMapper.newEntity(original);
        row.setEmail(null);

        Customer loaded = CustomerPersistenceMapper.toDomain(row);

        assertThat(loaded.getEmail()).isNull();
        assertThat(loaded.getMonthlyIncome()).isNull();
        assertThat(loaded.getCreditProfile().getCreditLimit()).isEqualTo(Money.usd(new BigDecimal("2500.00")));
    }

    @Test
    void copyIntoRefusesAmountsInAnotherCurrency() {
        Customer customer = Customer.create(CustomerId.of("CUST-MAP-3"), "Sara", "Ali", "sara@example.com", null,
            Money.usd(new BigDecimal("2500.00")));
        CustomerJpaEntity row = CustomerPersistenceMapper.newEntity(customer);
        Customer mixed = Customer.createWithCreditScore(CustomerId.of("CUST-MAP-3"), "Sara", "Ali", "sara@example.com",
            null, Money.aed(new BigDecimal("2000.00")), 650);
        mixed.updateContactInformation(null, null);

        CustomerPersistenceMapper.copyInto(mixed, row);
        assertThat(row.getCurrency()).isEqualTo("AED");
        assertThatThrownBy(() -> CustomerPersistenceMapper.copyInto(withIncomeIn(), row))
            .isInstanceOf(IllegalStateException.class);
    }

    private static Customer withIncomeIn() {
        return Customer.rehydrate(new com.bank.customer.domain.CustomerSnapshot(CustomerId.of("CUST-MAP-4"), "A", "B",
            null, null, Money.usd(new BigDecimal("100.00")), Money.usd(BigDecimal.ZERO), null,
            Money.aed(new BigDecimal("5000.00")), null, null, 0L));
    }
}
