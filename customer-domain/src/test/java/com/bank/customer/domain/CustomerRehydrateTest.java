package com.bank.customer.domain;

import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class CustomerRehydrateTest {

    private static final LocalDateTime CREATED = LocalDateTime.of(2024, 3, 1, 9, 0);
    private static final LocalDateTime UPDATED = LocalDateTime.of(2025, 1, 2, 10, 30);

    private static CustomerSnapshot legacySnapshot() {
        return new CustomerSnapshot(CustomerId.of("42"), "Amina", "Haddad", null, null,
            Money.aed(new BigDecimal("50000.00")), Money.aed(new BigDecimal("20000.00")),
            null, null, CREATED, UPDATED, 7L);
    }

    @Test
    void rehydrateRestoresStateWithoutRaisingEvents() {
        Customer customer = Customer.rehydrate(legacySnapshot());

        assertThat(customer.getId()).isEqualTo(CustomerId.of("42"));
        assertThat(customer.getFirstName()).isEqualTo("Amina");
        assertThat(customer.getEmail()).isNull();
        assertThat(customer.getCreditProfile().getAvailableCredit()).isEqualTo(Money.aed(new BigDecimal("30000.00")));
        assertThat(customer.getCreatedAt()).isEqualTo(CREATED);
        assertThat(customer.getUpdatedAt()).isEqualTo(UPDATED);
        assertThat(customer.getVersion()).isEqualTo(7L);
        assertThat(customer.getDomainEvents()).isEmpty();
    }

    @Test
    void rehydratedLegacyCustomerCanReserveAndRelease() {
        Customer customer = Customer.rehydrate(legacySnapshot());

        customer.reserveCredit(Money.aed(new BigDecimal("30000.00")));
        assertThatThrownBy(() -> customer.reserveCredit(Money.aed(new BigDecimal("0.01"))))
            .isInstanceOf(InsufficientCreditException.class);
        customer.releaseUntrackedCredit(Money.aed(new BigDecimal("50000.00")), Money.aed(BigDecimal.ZERO));

        assertThat(customer.getCreditProfile().getUsedCredit()).isEqualTo(Money.aed(BigDecimal.ZERO));
        assertThat(customer.getDomainEvents()).hasSize(2);
    }

    @Test
    void creditScoreUpdateKeepsTheAssignedLimitWhenNoIncomeIsOnFile() {
        Customer customer = Customer.rehydrate(legacySnapshot());

        customer.updateCreditScore(720);

        assertThat(customer.getCreditScore()).isEqualTo(720);
        assertThat(customer.getCreditProfile().getCreditLimit()).isEqualTo(Money.aed(new BigDecimal("50000.00")));
        assertThat(customer.getCreditProfile().getUsedCredit()).isEqualTo(Money.aed(new BigDecimal("20000.00")));
    }

    @Test
    void rehydrateRejectsInconsistentCredit() {
        CustomerSnapshot broken = new CustomerSnapshot(CustomerId.of("43"), "A", "B", null, null,
            Money.aed(new BigDecimal("100.00")), Money.aed(new BigDecimal("200.00")),
            null, null, CREATED, UPDATED, 0L);

        assertThatThrownBy(() -> Customer.rehydrate(broken)).isInstanceOf(IllegalArgumentException.class);
    }
}
