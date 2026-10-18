package com.bank.customer.domain.port.in;

import com.bank.shared.kernel.domain.CustomerId;

/** Reads a customer's full profile, personal data included. */
public interface GetCustomerProfileUseCase {

    /** @throws com.bank.customer.domain.CustomerNotFoundException if there is no such customer */
    CustomerProfile getCustomerProfile(CustomerId customerId);
}
