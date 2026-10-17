package com.bank.customer.infrastructure.web.dto;

import com.bank.customer.domain.Customer;
import com.bank.customer.domain.CustomerSnapshot;
import com.bank.customer.domain.port.in.CustomerProfile;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerResponseTest {

    @Test
    void fromShouldMapCustomerFields() {
        Customer customer = Customer.createWithCreditScore(
            CustomerId.of("CUST-RESP-001"),
            "Ali",
            "Sample",
            "ali@example.com",
            "+971500000001",
            Money.aed(new BigDecimal("2000.00")),
            700
        );
        customer.reserveCredit(Money.aed(new BigDecimal("500.00")));

        CustomerResponse response = CustomerResponse.from(CustomerProfile.of(customer));

        assertThat(response.customerId()).isEqualTo("CUST-RESP-001");
        assertThat(response.firstName()).isEqualTo("Ali");
        assertThat(response.lastName()).isEqualTo("Sample");
        assertThat(response.email()).isEqualTo("ali@example.com");
        assertThat(response.creditLimit()).isEqualByComparingTo("8000.00");
        assertThat(response.usedCredit()).isEqualByComparingTo("500.00");
        assertThat(response.availableCredit()).isEqualByComparingTo("7500.00");
        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.creditScore()).isEqualTo(700);
        assertThat(response.monthlyIncome()).isEqualByComparingTo("2000.00");
        assertThat(response.createdAt()).isNotNull();
        assertThat(response.lastModifiedAt()).isNotNull();
    }

    @Test
    void legacyCustomerWithoutContactDetailsOrTimestampsMapsToNulls() {
        Customer legacy = Customer.rehydrate(new CustomerSnapshot(
            CustomerId.of("7"), "Legacy", "Customer", null, null,
            Money.aed(new BigDecimal("1000.00")), Money.aed(BigDecimal.ZERO), null, null, null, null, 0L));

        CustomerResponse response = CustomerResponse.from(CustomerProfile.of(legacy));

        assertThat(response.email()).isNull();
        assertThat(response.monthlyIncome()).isNull();
        assertThat(response.createdAt()).isNull();
        assertThat(response.availableCredit()).isEqualByComparingTo("1000.00");
    }
}
