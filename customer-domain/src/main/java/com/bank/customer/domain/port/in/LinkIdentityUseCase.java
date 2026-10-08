package com.bank.customer.domain.port.in;

/**
 * Onboarding step that links a registered customer to the end user's identity
 * account (staff only): records the link and sets the user's customer_id
 * attribute, so the user's tokens carry the customer_id claim. Re-running it
 * for the same user is safe and repairs a missing attribute.
 */
public interface LinkIdentityUseCase {

    /**
     * @throws com.bank.customer.domain.CustomerNotFoundException if there is no such customer
     * @throws com.bank.customer.domain.IdentityLinkConflictException if either side is linked elsewhere
     * @throws com.bank.customer.domain.IdentityUserNotFoundException if the identity user does not exist
     * @throws com.bank.customer.domain.IdentityDirectoryUnavailableException if the directory cannot be updated
     */
    IdentityLink linkIdentity(LinkIdentityCommand command);
}
