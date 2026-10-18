package com.bank.customer.infrastructure.web.dto;

import com.bank.customer.domain.port.in.ScreeningProfile;

/**
 * Screening profile of a customer (ScreeningProfileResponse in
 * customer-context.yaml): the name and the KYC status for sanctions and PEP
 * matching by the compliance service, and nothing else. kycStatus is the same
 * enum as KycStatusResponse.kycStatus.
 */
public record ScreeningProfileResponse(
    String customerId,
    String firstName,
    String lastName,
    String kycStatus
) {

    public static ScreeningProfileResponse from(ScreeningProfile profile) {
        return new ScreeningProfileResponse(profile.customerId().getValue(), profile.firstName(), profile.lastName(),
            profile.kycStatus().status().name());
    }
}
