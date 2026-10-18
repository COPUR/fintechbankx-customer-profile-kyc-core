package com.bank.customer.domain.port.in;

import com.bank.shared.kernel.domain.CustomerId;

/** Reads a customer's credit standing only (position and credit score), without personal data. */
public interface GetCreditStandingUseCase {
    /** @throws com.bank.customer.domain.CustomerNotFoundException if there is no such customer */
    CreditStanding getCreditStanding(CustomerId customerId);
}
