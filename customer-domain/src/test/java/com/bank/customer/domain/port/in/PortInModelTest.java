package com.bank.customer.domain.port.in;

import com.bank.customer.domain.Customer;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PortInModelTest {

    private static Money aed(String amount) {
        return Money.aed(new BigDecimal(amount));
    }

    @Test
    void registrationAcceptsLimitsFromOneThousandToOneMillion() {
        assertThat(new RegisterCustomerCommand("Ali", "Sample", "ali@example.com", null, aed("1000.00"))
            .initialCreditLimit()).isEqualTo(aed("1000.00"));
        assertThat(new RegisterCustomerCommand("Ali", "Sample", "ali@example.com", null, aed("1000000.00"))
            .initialCreditLimit()).isEqualTo(aed("1000000.00"));
    }

    @Test
    void registrationRejectsShortNamesAndLimitsOutOfRange() {
        assertThatThrownBy(() -> new RegisterCustomerCommand("A", "Sample", "a@example.com", null, aed("5000")))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("First name must be at least 2 characters");
        assertThatThrownBy(() -> new RegisterCustomerCommand(null, "Sample", "a@example.com", null, aed("5000")))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("First name must be at least 2 characters");
        assertThatThrownBy(() -> new RegisterCustomerCommand("Ali", " S ", "a@example.com", null, aed("5000")))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Last name must be at least 2 characters");
        assertThatThrownBy(() -> new RegisterCustomerCommand("Ali", "Sample", "a@example.com", null, aed("999.99")))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Minimum credit limit is $1,000");
        assertThatThrownBy(() -> new RegisterCustomerCommand("Ali", "Sample", "a@example.com", null, aed("1000000.01")))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Maximum credit limit is $1,000,000");
        assertThatNullPointerException()
            .isThrownBy(() -> new RegisterCustomerCommand("Ali", "Sample", "a@example.com", null, null));
    }

    @Test
    void creditMovementNeedsAnIdempotencyKey() {
        CustomerId id = CustomerId.of("CUST-1");

        assertThat(new CreditMovementCommand(id, aed("10.00"), "k-1", null).reference()).isNull();
        assertThatThrownBy(() -> new CreditMovementCommand(id, aed("10.00"), " ", null))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Idempotency key is required");
        assertThatThrownBy(() -> new CreditMovementCommand(id, aed("10.00"), null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatNullPointerException().isThrownBy(() -> new CreditMovementCommand(null, aed("10.00"), "k", null));
        assertThatNullPointerException().isThrownBy(() -> new CreditMovementCommand(id, null, "k", null));
    }

    @Test
    void profileAndCreditPositionReflectTheAggregate() {
        Customer customer = Customer.create(CustomerId.of("CUST-2"), "Ali", "Sample", "ali@example.com",
            "+971500000001", aed("5000.00"));
        customer.reserveCredit(aed("1250.00"));

        CustomerProfile profile = CustomerProfile.of(customer);

        assertThat(profile.customerId()).isEqualTo(CustomerId.of("CUST-2"));
        assertThat(profile.email()).isEqualTo("ali@example.com");
        assertThat(profile.credit()).isEqualTo(new CreditPosition(
            CustomerId.of("CUST-2"), aed("5000.00"), aed("1250.00"), aed("3750.00")));
        assertThat(profile.kycStatus()).isEqualTo(com.bank.customer.domain.KycStatus.pending());
        assertThat(profile.createdAt()).isNotNull();
    }

    /** The standing the eligibility decider reads: the position and the score; null score for a customer without one. */
    @Test
    void creditStandingCarriesThePositionAndTheScore() {
        Customer scored = Customer.createWithCreditScore(CustomerId.of("CUST-3"), "Ali", "Sample", "ali@example.com",
            "+971500000001", aed("5000.00"), 712);
        Customer unscored = Customer.create(CustomerId.of("CUST-4"), "Ali", "Sample", "ali@example.com",
            "+971500000001", aed("5000.00"));

        assertThat(CreditStanding.of(scored)).isEqualTo(new CreditStanding(CreditPosition.of(scored), 712));
        assertThat(CreditStanding.of(unscored).creditScore()).isNull();
        assertThatNullPointerException().isThrownBy(() -> new CreditStanding(null, 712));
    }

    /** The screening profile is the name and the KYC status, nothing else; a customer id and a status are required. */
    @Test
    void screeningProfileCarriesTheNameAndTheKycStatusOnly() {
        Customer customer = Customer.create(CustomerId.of("CUST-5"), "Ali", "Sample", "ali@example.com",
            "+971500000001", aed("5000.00"));

        assertThat(ScreeningProfile.of(customer)).isEqualTo(new ScreeningProfile(CustomerId.of("CUST-5"), "Ali", "Sample",
            com.bank.customer.domain.KycStatus.pending()));
        assertThat(ScreeningProfile.class.getRecordComponents()).extracting(java.lang.reflect.RecordComponent::getName)
            .containsExactly("customerId", "firstName", "lastName", "kycStatus");
        assertThatNullPointerException().isThrownBy(() -> new ScreeningProfile(null, "Ali", "Sample",
            com.bank.customer.domain.KycStatus.pending()));
        assertThatNullPointerException().isThrownBy(() -> new ScreeningProfile(CustomerId.of("CUST-5"), "Ali", "Sample", null));
    }
}
