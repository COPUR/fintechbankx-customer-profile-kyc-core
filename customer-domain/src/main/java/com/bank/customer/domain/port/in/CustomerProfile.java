package com.bank.customer.domain.port.in;

import com.bank.customer.domain.Customer;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;

import java.time.LocalDateTime;

/**
 * Read model of a customer for staff and the customer themselves. email,
 * phoneNumber, creditScore, monthlyIncome and the timestamps may be null for
 * customers migrated from the monolith.
 */
public record CustomerProfile(
    CustomerId customerId,
    String firstName,
    String lastName,
    String email,
    String phoneNumber,
    CreditPosition credit,
    Integer creditScore,
    Money monthlyIncome,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {

    public static CustomerProfile of(Customer customer) {
        return new CustomerProfile(customer.getId(), customer.getFirstName(), customer.getLastName(),
            customer.getEmail(), customer.getPhoneNumber(), CreditPosition.of(customer), customer.getCreditScore(),
            customer.getMonthlyIncome(), customer.getCreatedAt(), customer.getUpdatedAt());
    }
}
