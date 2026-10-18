package com.bank.customer.infrastructure.web.dto;

import com.bank.customer.domain.port.in.CreditStanding;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

/**
 * Credit standing of a customer without any personal data
 * (CreditStandingResponse in customer-context.yaml): what the eligibility
 * decider (risk decisioning) reads on GET .../credit-standing. creditScore is
 * absent, not null, for a customer without a score. Lending reads the
 * position without the score through {@link CustomerCreditResponse}.
 */
public record CreditStandingResponse(
    String customerId,
    String currency,
    BigDecimal creditLimit,
    BigDecimal usedCredit,
    BigDecimal availableCredit,
    @JsonInclude(JsonInclude.Include.NON_NULL) Integer creditScore
) {

    public static CreditStandingResponse from(CreditStanding standing) {
        var credit = standing.position();
        return new CreditStandingResponse(
            credit.customerId().getValue(),
            credit.creditLimit().getCurrency().getCurrencyCode(),
            credit.creditLimit().getAmount(),
            credit.usedCredit().getAmount(),
            credit.availableCredit().getAmount(),
            standing.creditScore());
    }
}
