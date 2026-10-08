package com.bank.customer.application.dto;

import com.bank.customer.domain.Customer;

import java.math.BigDecimal;

/**
 * Credit position of a customer without any personal data: what service
 * callers such as the loan service need to decide on a loan.
 */
public record CustomerCreditResponse(
    String customerId,
    String currency,
    BigDecimal creditLimit,
    BigDecimal usedCredit,
    BigDecimal availableCredit
) {

    public static CustomerCreditResponse from(Customer customer) {
        var credit = customer.getCreditProfile();
        return new CustomerCreditResponse(
            customer.getId().getValue(),
            credit.getCreditLimit().getCurrency().getCurrencyCode(),
            credit.getCreditLimit().getAmount(),
            credit.getUsedCredit().getAmount(),
            credit.getAvailableCredit().getAmount());
    }
}
