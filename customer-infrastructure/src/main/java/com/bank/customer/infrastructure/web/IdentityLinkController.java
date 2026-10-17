package com.bank.customer.infrastructure.web;

import com.bank.customer.domain.IdentityUserId;
import com.bank.customer.domain.port.in.IdentityLink;
import com.bank.customer.domain.port.in.LinkIdentityCommand;
import com.bank.customer.domain.port.in.LinkIdentityUseCase;
import com.bank.shared.kernel.domain.CustomerId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Onboarding step: staff link a registered customer to the end user's
 * Keycloak account once the user exists and is verified. The service records
 * the link and sets the user attribute customer_id, so the user's web, mobile
 * and TPP tokens carry the customer_id claim. PUT, because repeating it for
 * the same user is safe.
 */
@RestController
@RequestMapping("/api/v1/customers")
public class IdentityLinkController {

    private final LinkIdentityUseCase linkIdentity;

    public IdentityLinkController(LinkIdentityUseCase linkIdentity) {
        this.linkIdentity = linkIdentity;
    }

    @PutMapping("/{customerId}/identity-link")
    @PreAuthorize("hasAnyRole('BANKER', 'ADMIN')")
    public ResponseEntity<IdentityLinkResponse> linkIdentity(@PathVariable String customerId,
                                                             @Valid @RequestBody IdentityLinkRequest request) {
        IdentityLink link = linkIdentity.linkIdentity(
            new LinkIdentityCommand(CustomerId.of(customerId), new IdentityUserId(request.identityUserId())));
        return ResponseEntity.ok(new IdentityLinkResponse(link.customerId().getValue(), link.identityUserId().value()));
    }

    /** identityUserId: the Keycloak user id (token "sub") of the end user. */
    public record IdentityLinkRequest(@NotBlank(message = "identityUserId is required") String identityUserId) {
    }

    public record IdentityLinkResponse(String customerId, String identityUserId) {
    }
}
