package com.bank.customer.domain.port.in;

import com.bank.customer.domain.KycStatus;
import com.bank.shared.kernel.domain.CustomerId;

import java.util.Objects;

/** Staff decision on a customer's KYC: VERIFIED or REJECTED, by the staff subject. */
public record ChangeKycStatusCommand(CustomerId customerId, KycStatus.Status status, String updatedBy) {

    public ChangeKycStatusCommand {
        Objects.requireNonNull(customerId, "customerId");
        if (status != KycStatus.Status.VERIFIED && status != KycStatus.Status.REJECTED) {
            throw new IllegalArgumentException("Staff set the KYC status to VERIFIED or REJECTED");
        }
    }
}
