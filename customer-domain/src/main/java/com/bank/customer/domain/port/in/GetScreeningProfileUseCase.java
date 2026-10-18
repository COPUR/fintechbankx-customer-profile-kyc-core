package com.bank.customer.domain.port.in;

import com.bank.shared.kernel.domain.CustomerId;

/** Reads a customer's screening profile only (name and KYC status), for sanctions and PEP matching. */
public interface GetScreeningProfileUseCase {
    /** @throws com.bank.customer.domain.CustomerNotFoundException if there is no such customer */
    ScreeningProfile getScreeningProfile(CustomerId customerId);
}
