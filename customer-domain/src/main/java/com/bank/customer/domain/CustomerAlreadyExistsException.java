package com.bank.customer.domain;

/**
 * A customer with the same e-mail address (compared case-insensitively) is
 * already registered. The message names no personal data, so it is safe to
 * return to the caller and to log.
 */
public class CustomerAlreadyExistsException extends RuntimeException {

    public CustomerAlreadyExistsException() {
        super("A customer with this e-mail address already exists");
    }

    public CustomerAlreadyExistsException(Throwable cause) {
        super("A customer with this e-mail address already exists", cause);
    }
}
