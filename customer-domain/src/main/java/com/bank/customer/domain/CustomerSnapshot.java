package com.bank.customer.domain;

import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;

import java.time.LocalDateTime;

/**
 * Persisted state of a {@link Customer}, used by persistence adapters to
 * rebuild the aggregate without replaying creation rules or raising events.
 * email, phoneNumber, creditScore and monthlyIncome may be null for customers
 * migrated from the monolith, which never stored them. identityUserId is null
 * until onboarding links the customer to an identity user.
 */
public record CustomerSnapshot(
    CustomerId customerId,
    String firstName,
    String lastName,
    String email,
    String phoneNumber,
    Money creditLimit,
    Money usedCredit,
    Integer creditScore,
    Money monthlyIncome,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    Long version,
    IdentityUserId identityUserId
) {

    /** A customer not linked to an identity user. */
    public CustomerSnapshot(CustomerId customerId, String firstName, String lastName, String email,
                            String phoneNumber, Money creditLimit, Money usedCredit, Integer creditScore,
                            Money monthlyIncome, LocalDateTime createdAt, LocalDateTime updatedAt, Long version) {
        this(customerId, firstName, lastName, email, phoneNumber, creditLimit, usedCredit, creditScore,
            monthlyIncome, createdAt, updatedAt, version, null);
    }
}
