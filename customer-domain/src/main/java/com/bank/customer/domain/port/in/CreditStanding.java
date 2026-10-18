package com.bank.customer.domain.port.in;

import com.bank.customer.domain.Customer;

import java.util.Objects;

/**
 * What the eligibility decider (risk decisioning, ADR-031 decision 2) may
 * read about a customer: the credit position and the credit score, no
 * personal data. The score is restricted data: it never travels on an event
 * (Customer.Customer.CreditScoreUpdated.v1 says only that it changed) and
 * lending, which only reserves and releases credit, reads the position
 * without it ({@link CreditPosition}). The score is null for a customer who
 * has none yet (for example one migrated without a score).
 */
public record CreditStanding(CreditPosition position, Integer creditScore) {

    public CreditStanding {
        Objects.requireNonNull(position, "Credit position cannot be null");
    }

    public static CreditStanding of(Customer customer) {
        return new CreditStanding(CreditPosition.of(customer), customer.getCreditScore());
    }
}
