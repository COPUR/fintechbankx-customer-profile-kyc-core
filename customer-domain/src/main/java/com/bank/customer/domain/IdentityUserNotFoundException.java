package com.bank.customer.domain;

/** The identity provider has no user with the given id. */
public class IdentityUserNotFoundException extends RuntimeException {

    public IdentityUserNotFoundException() {
        super("No identity user with this id");
    }
}
