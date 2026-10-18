package com.bank.customer.domain.port.in;

import com.bank.customer.domain.Customer;
import com.bank.customer.domain.KycStatus;
import com.bank.shared.kernel.domain.CustomerId;

import java.util.Objects;

/**
 * What the compliance service may read about a customer for sanctions and
 * PEP name matching: the name and the KYC status, nothing else. No contact
 * details, no credit figures, no credit score, no income: those stay on the
 * full profile (staff and the customer) and the credit reads (credit
 * deciders).
 */
public record ScreeningProfile(CustomerId customerId, String firstName, String lastName, KycStatus kycStatus) {

    public ScreeningProfile {
        Objects.requireNonNull(customerId, "Customer ID cannot be null");
        Objects.requireNonNull(kycStatus, "KYC status cannot be null");
    }

    public static ScreeningProfile of(Customer customer) {
        return new ScreeningProfile(customer.getId(), customer.getFirstName(), customer.getLastName(), customer.getKycStatus());
    }
}
