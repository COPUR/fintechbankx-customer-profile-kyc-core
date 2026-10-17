package com.bank.customer.domain.port.in;

import com.bank.customer.domain.IdentityUserId;
import com.bank.shared.kernel.domain.CustomerId;

/** A customer profile and the identity user it is linked to. */
public record IdentityLink(CustomerId customerId, IdentityUserId identityUserId) {
}
