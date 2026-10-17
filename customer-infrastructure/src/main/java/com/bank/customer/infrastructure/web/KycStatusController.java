package com.bank.customer.infrastructure.web;

import com.bank.customer.domain.KycStatus;
import com.bank.customer.domain.port.in.ChangeKycStatusCommand;
import com.bank.customer.domain.port.in.ChangeKycStatusUseCase;
import com.bank.customer.domain.port.in.GetKycStatusUseCase;
import com.bank.customer.infrastructure.web.dto.KycStatusResponse;
import com.bank.shared.kernel.domain.CustomerId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * KYC status of a customer. The payment service reads it with a SERVICE
 * token whose client (azp) is on SERVICE_CALLERS_KYC, a list separate from
 * the credit callers; staff and the customer themselves read it too. Only
 * staff (BANKER, ADMIN) verify or reject; the token subject is recorded as
 * kyc_updated_by. Neither answer carries personal data.
 */
@RestController
@RequestMapping("/api/v1/customers/{customerId}/kyc-status")
public class KycStatusController {

    static final String KYC_READERS = "hasAnyRole('BANKER', 'ADMIN')"
        + " or (hasRole('SERVICE') and @kycServiceCallers.allowed(authentication))"
        + " or (hasRole('CUSTOMER') and #customerId == authentication.name)";

    private final GetKycStatusUseCase getKycStatus;
    private final ChangeKycStatusUseCase changeKycStatus;

    public KycStatusController(GetKycStatusUseCase getKycStatus, ChangeKycStatusUseCase changeKycStatus) {
        this.getKycStatus = getKycStatus;
        this.changeKycStatus = changeKycStatus;
    }

    @GetMapping
    @PreAuthorize(KYC_READERS)
    public ResponseEntity<KycStatusResponse> getKycStatus(@PathVariable String customerId) {
        return ResponseEntity.ok(KycStatusResponse.from(getKycStatus.getKycStatus(CustomerId.of(customerId))));
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('BANKER', 'ADMIN')")
    public ResponseEntity<KycStatusResponse> changeKycStatus(@PathVariable String customerId,
                                                             @Valid @RequestBody ChangeKycStatusRequest request,
                                                             JwtAuthenticationToken authentication) {
        ChangeKycStatusCommand command = new ChangeKycStatusCommand(CustomerId.of(customerId), request.status(),
            authentication.getToken().getSubject());
        return ResponseEntity.ok(KycStatusResponse.from(changeKycStatus.changeKycStatus(command)));
    }

    /** status: VERIFIED or REJECTED; PENDING or anything else is a 400. */
    public record ChangeKycStatusRequest(@NotNull(message = "status is required") KycStatus.Status status) {
    }
}
