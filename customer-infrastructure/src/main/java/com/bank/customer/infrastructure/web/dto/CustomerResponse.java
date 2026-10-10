package com.bank.customer.infrastructure.web.dto;

import com.bank.customer.domain.port.in.CustomerProfile;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Customer profile response (CustomerResponse in customer-context.yaml), for
 * staff and the customer themselves.
 */
public record CustomerResponse(
    String customerId,
    String firstName,
    String lastName,
    String email,
    String phoneNumber,
    BigDecimal creditLimit,
    BigDecimal usedCredit,
    BigDecimal availableCredit,
    String status,
    Integer creditScore,
    BigDecimal monthlyIncome,
    Instant createdAt,
    Instant lastModifiedAt
) {

    public static CustomerResponse from(CustomerProfile profile) {
        return new CustomerResponse(
            profile.customerId().getValue(),
            profile.firstName(),
            profile.lastName(),
            profile.email(),
            profile.phoneNumber(),
            profile.credit().creditLimit().getAmount(),
            profile.credit().usedCredit().getAmount(),
            profile.credit().availableCredit().getAmount(),
            "ACTIVE", // Default status
            profile.creditScore(),
            profile.monthlyIncome() != null ? profile.monthlyIncome().getAmount() : null,
            toInstant(profile.createdAt()),
            toInstant(profile.updatedAt()));
    }

    private static Instant toInstant(LocalDateTime time) {
        return time == null ? null : time.atZone(ZoneOffset.UTC).toInstant();
    }
}
