package com.bank.customer.domain.port.in;

import com.bank.shared.kernel.domain.CustomerId;

/** Reads a customer's KYC status only, without personal data. */
public interface GetKycStatusUseCase {

    /** @throws com.bank.customer.domain.CustomerNotFoundException if there is no such customer */
    KycStatusView getKycStatus(CustomerId customerId);
}
