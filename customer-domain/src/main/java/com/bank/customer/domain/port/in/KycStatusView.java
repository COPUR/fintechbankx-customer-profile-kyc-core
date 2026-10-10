package com.bank.customer.domain.port.in;

import com.bank.customer.domain.Customer;
import com.bank.customer.domain.KycStatus;
import com.bank.shared.kernel.domain.CustomerId;

/** A customer's KYC status without personal data: what the payment service may see. */
public record KycStatusView(CustomerId customerId, KycStatus kycStatus) {

    public static KycStatusView of(Customer customer) {
        return new KycStatusView(customer.getId(), customer.getKycStatus());
    }

    public boolean kycVerified() {
        return kycStatus.isVerified();
    }
}
