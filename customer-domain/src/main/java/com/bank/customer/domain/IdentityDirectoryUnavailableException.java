package com.bank.customer.domain;

/**
 * The identity directory could not be updated (disabled, unreachable or
 * refusing this service). The link is not recorded; the caller may retry.
 */
public class IdentityDirectoryUnavailableException extends RuntimeException {

    public IdentityDirectoryUnavailableException(String message) {
        super(message);
    }

    public IdentityDirectoryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
