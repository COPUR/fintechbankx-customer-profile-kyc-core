package com.bank.customer.domain.port.in;

import com.bank.shared.kernel.domain.Money;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Registers a customer with an assigned credit limit. The registration rules
 * (names of at least two characters, an initial limit from 1,000 to 1,000,000)
 * are checked here; the customer aggregate checks the rest (valid e-mail).
 */
public record RegisterCustomerCommand(
    String firstName,
    String lastName,
    String email,
    String phoneNumber,
    Money initialCreditLimit
) {

    static final BigDecimal MIN_INITIAL_LIMIT = BigDecimal.valueOf(1000);
    static final BigDecimal MAX_INITIAL_LIMIT = BigDecimal.valueOf(1000000);

    public RegisterCustomerCommand {
        if (firstName == null || firstName.trim().length() < 2) {
            throw new IllegalArgumentException("First name must be at least 2 characters");
        }
        if (lastName == null || lastName.trim().length() < 2) {
            throw new IllegalArgumentException("Last name must be at least 2 characters");
        }
        Objects.requireNonNull(initialCreditLimit, "Initial credit limit is required");
        if (initialCreditLimit.getAmount().compareTo(MIN_INITIAL_LIMIT) < 0) {
            throw new IllegalArgumentException("Minimum credit limit is $1,000");
        }
        if (initialCreditLimit.getAmount().compareTo(MAX_INITIAL_LIMIT) > 0) {
            throw new IllegalArgumentException("Maximum credit limit is $1,000,000");
        }
    }
}
