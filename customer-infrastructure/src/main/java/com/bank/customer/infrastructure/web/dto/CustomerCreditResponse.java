package com.bank.customer.infrastructure.web.dto;

import com.bank.customer.domain.port.in.CreditPosition;

import java.math.BigDecimal;

/**
 * Credit position of a customer without any personal data
 * (CustomerCreditResponse in customer-context.yaml): what service callers such
 * as the loan service need to decide on a loan.
 */
public record CustomerCreditResponse(
    String customerId,
    String currency,
    BigDecimal creditLimit,
    BigDecimal usedCredit,
    BigDecimal availableCredit
) {

    public static CustomerCreditResponse from(CreditPosition credit) {
        return new CustomerCreditResponse(
            credit.customerId().getValue(),
            credit.creditLimit().getCurrency().getCurrencyCode(),
            credit.creditLimit().getAmount(),
            credit.usedCredit().getAmount(),
            credit.availableCredit().getAmount());
    }
}
