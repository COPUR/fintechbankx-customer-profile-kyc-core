package com.bank.customer.domain;

/**
 * Domain exception: a credit limit may not be set below the credit the
 * customer already uses (used_credit <= credit_limit at all times).
 */
public class CreditLimitBelowUsedCreditException extends RuntimeException {

    public CreditLimitBelowUsedCreditException(String message) {
        super(message);
    }
}
