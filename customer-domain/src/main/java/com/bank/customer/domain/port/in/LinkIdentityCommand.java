package com.bank.customer.domain.port.in;

import com.bank.customer.domain.IdentityUserId;
import com.bank.shared.kernel.domain.CustomerId;

import java.util.Objects;

/** Link a customer profile to the end user's identity account. */
public record LinkIdentityCommand(CustomerId customerId, IdentityUserId identityUserId) {

    public LinkIdentityCommand {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(identityUserId, "identityUserId");
    }
}
