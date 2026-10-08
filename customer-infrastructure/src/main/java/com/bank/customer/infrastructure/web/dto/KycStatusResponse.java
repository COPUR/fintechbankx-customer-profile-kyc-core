package com.bank.customer.infrastructure.web.dto;

import com.bank.customer.domain.port.in.KycStatusView;

import java.time.Instant;

/**
 * KYC status of a customer without any personal data (KycStatusResponse in
 * customer-context.yaml): what the payment service needs. verifiedAt is null
 * unless kycStatus is VERIFIED.
 */
public record KycStatusResponse(
    String customerId,
    boolean kycVerified,
    String kycStatus,
    String kycSource,
    Instant verifiedAt
) {

    public static KycStatusResponse from(KycStatusView view) {
        return new KycStatusResponse(view.customerId().getValue(), view.kycVerified(),
            view.kycStatus().status().name(), view.kycStatus().source().name(), view.kycStatus().verifiedAt());
    }
}
