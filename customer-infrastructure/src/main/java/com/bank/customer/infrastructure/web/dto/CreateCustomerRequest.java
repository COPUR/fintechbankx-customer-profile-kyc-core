package com.bank.customer.infrastructure.web.dto;

import com.bank.customer.domain.port.in.RegisterCustomerCommand;
import com.bank.shared.kernel.domain.Money;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.Currency;

/**
 * POST /api/v1/customers body (CreateCustomerRequest in customer-context.yaml).
 * The registration rules themselves are in {@link RegisterCustomerCommand}.
 */
public record CreateCustomerRequest(
    @NotBlank(message = "First name is required")
    String firstName,

    @NotBlank(message = "Last name is required")
    String lastName,

    @Email(message = "Valid email is required")
    @NotBlank(message = "Email is required")
    String email,

    String phoneNumber,

    @NotNull(message = "Initial credit limit is required")
    @Positive(message = "Credit limit must be positive")
    BigDecimal initialCreditLimit,

    String currency
) {

    /** The currency defaults to USD when omitted, as in the monolith. */
    public Money getCreditLimitAsMoney() {
        Currency curr = currency != null ? Currency.getInstance(currency) : Currency.getInstance("USD");
        return Money.of(initialCreditLimit, curr);
    }

    public RegisterCustomerCommand toCommand() {
        return new RegisterCustomerCommand(firstName, lastName, email, phoneNumber, getCreditLimitAsMoney());
    }
}
