package com.bank.customer.domain;

import com.bank.shared.kernel.domain.ValueObject;

import java.util.regex.Pattern;

/**
 * Id of the end user's account in the identity provider (the Keycloak user id,
 * which is the token "sub"). Restricted to letters, digits and hyphens so it
 * is safe in an admin-API path.
 */
public record IdentityUserId(String value) implements ValueObject {

    private static final Pattern ALLOWED = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

    public IdentityUserId {
        if (value == null || !ALLOWED.matcher(value).matches()) {
            throw new IllegalArgumentException("Identity user id must be 1 to 64 letters, digits or hyphens");
        }
    }
}
