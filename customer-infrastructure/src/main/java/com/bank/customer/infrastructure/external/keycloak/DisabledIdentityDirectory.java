package com.bank.customer.infrastructure.external.keycloak;

import com.bank.customer.domain.IdentityDirectoryUnavailableException;
import com.bank.customer.domain.IdentityUserId;
import com.bank.customer.domain.port.out.IdentityDirectoryPort;
import com.bank.shared.kernel.domain.CustomerId;

/**
 * Used while IDENTITY_ADMIN_ENABLED is false (the default, so the service
 * boots without Keycloak admin access). Fails closed: a link that cannot set
 * customer_id on the user is refused and not recorded, because a recorded link
 * whose user tokens lack the claim would look complete and silently break the
 * end user's open-finance access.
 */
public class DisabledIdentityDirectory implements IdentityDirectoryPort {

    @Override
    public void linkCustomer(IdentityUserId userId, CustomerId customerId) {
        throw new IdentityDirectoryUnavailableException(
            "Identity directory integration is disabled (IDENTITY_ADMIN_ENABLED=false)");
    }
}
