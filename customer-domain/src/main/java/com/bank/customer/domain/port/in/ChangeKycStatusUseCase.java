package com.bank.customer.domain.port.in;

/** Staff verify or reject a customer's KYC; the change is published as an event. */
public interface ChangeKycStatusUseCase {

    /** @throws com.bank.customer.domain.CustomerNotFoundException if there is no such customer */
    KycStatusView changeKycStatus(ChangeKycStatusCommand command);
}
