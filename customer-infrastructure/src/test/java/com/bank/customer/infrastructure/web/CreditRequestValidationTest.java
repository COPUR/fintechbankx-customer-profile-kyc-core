package com.bank.customer.infrastructure.web;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class CreditRequestValidationTest {

    private static jakarta.validation.ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    void aPositiveAmountInAnIsoCurrencyIsValid() {
        assertThat(validator.validate(new CustomerController.ReserveCreditRequest(new BigDecimal("0.01"), "AED", "LOAN-1"))).isEmpty();
        assertThat(validator.validate(new CustomerController.ReleaseCreditRequest(new BigDecimal("10"), "USD", null))).isEmpty();
        assertThat(validator.validate(new CustomerController.UpdateCreditLimitRequest(new BigDecimal("5000"), "EUR"))).isEmpty();
    }

    @Test
    void missingOrNonPositiveAmountsAndUnknownCurrenciesAreRejected() {
        assertThat(messages(validator.validate(new CustomerController.ReserveCreditRequest(null, "AED", null))))
            .containsExactly("amount is required");
        assertThat(messages(validator.validate(new CustomerController.ReserveCreditRequest(BigDecimal.ZERO, "AED", null))))
            .containsExactly("amount must be positive");
        assertThat(messages(validator.validate(new CustomerController.ReleaseCreditRequest(new BigDecimal("-1"), "AED", null))))
            .containsExactly("amount must be positive");
        assertThat(messages(validator.validate(new CustomerController.ReleaseCreditRequest(BigDecimal.TEN, null, null))))
            .containsExactly("currency is required");
        assertThat(messages(validator.validate(new CustomerController.UpdateCreditLimitRequest(BigDecimal.TEN, "ZZZ"))))
            .containsExactly("currency must be an ISO 4217 code");
        assertThat(messages(validator.validate(new CustomerController.UpdateCreditLimitRequest(null, null))))
            .containsExactlyInAnyOrder("amount is required", "currency is required");
    }

    private static Set<String> messages(Set<? extends ConstraintViolation<?>> violations) {
        return violations.stream().map(ConstraintViolation::getMessage).collect(Collectors.toSet());
    }
}
