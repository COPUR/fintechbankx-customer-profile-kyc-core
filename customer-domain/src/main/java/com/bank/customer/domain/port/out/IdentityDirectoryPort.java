package com.bank.customer.domain.port.out;

import com.bank.customer.domain.IdentityUserId;
import com.bank.shared.kernel.domain.CustomerId;

/**
 * The identity provider's user directory. Setting the user attribute
 * customer_id makes the provider put the customer_id claim into that user's
 * access tokens (platform contract, "End-user and caller claims").
 */
public interface IdentityDirectoryPort {

    /**
     * Sets the user's customer_id attribute to the customer id. Idempotent: a
     * user that already carries this customer id is left as it is.
     *
     * @throws com.bank.customer.domain.IdentityUserNotFoundException if there is no such user
     * @throws com.bank.customer.domain.IdentityLinkConflictException if the user carries another customer id
     * @throws com.bank.customer.domain.IdentityDirectoryUnavailableException if the directory cannot be updated
     */
    void linkCustomer(IdentityUserId userId, CustomerId customerId);
}
