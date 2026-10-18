package com.bank.customer.domain;

/**
 * The customer is already linked to a different identity user, or the identity
 * user already carries a different customer id. One user, one customer
 * profile; changing a link is a manual, audited operation.
 */
public class IdentityLinkConflictException extends RuntimeException {

    public IdentityLinkConflictException() {
        super("The customer or the identity user is already linked elsewhere");
    }

    public IdentityLinkConflictException(Throwable cause) {
        super("The customer or the identity user is already linked elsewhere", cause);
    }
}
