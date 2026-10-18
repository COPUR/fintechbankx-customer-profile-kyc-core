package com.bank.customer.domain;

import java.util.Currency;

/**
 * Domain exception: a credit move is in a valid currency other than the one
 * the customer's credit is held in. Never converted, never "insufficient".
 */
public class CreditCurrencyMismatchException extends RuntimeException {

    public CreditCurrencyMismatchException(Currency held, Currency requested) {
        super("Credit is held in " + held.getCurrencyCode() + ", the request is in " + requested.getCurrencyCode());
    }
}
