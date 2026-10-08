package com.bank.customer.domain.port.in;

import com.bank.shared.kernel.domain.CustomerId;

/** Reads a customer's credit position only, without personal data. */
public interface GetCreditPositionUseCase {

    /** @throws com.bank.customer.domain.CustomerNotFoundException if there is no such customer */
    CreditPosition getCreditPosition(CustomerId customerId);
}
