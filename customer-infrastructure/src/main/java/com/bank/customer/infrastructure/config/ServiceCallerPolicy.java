package com.bank.customer.infrastructure.config;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which services may call a group of endpoints. The SERVICE realm role
 * is held by every client-credentials client in the realm, so on its own it
 * would let any service do this; the token's authorized party (azp, the
 * calling client id) must also be on this list. One list per endpoint group
 * (SecurityConfiguration): {@code @serviceCallers} for the credit position and
 * movements (SERVICE_CALLERS, lending), {@code @kycServiceCallers} for the KYC
 * status (SERVICE_CALLERS_KYC, payments), {@code @creditStandingServiceCallers}
 * for the position with the credit score (SERVICE_CALLERS_CREDIT_STANDING, risk
 * decisioning) and {@code @screeningServiceCallers} for the name and KYC
 * status (SERVICE_CALLERS_SCREENING, compliance), used from @PreAuthorize as
 * {@code @serviceCallers.allowed(authentication)}. Fail closed: an empty or
 * blank list (entries are trimmed, empty ones dropped) allows no client at
 * all, and a token without azp is never allowed.
 */
public class ServiceCallerPolicy {

    private final Set<String> allowedClients;

    public ServiceCallerPolicy(String allowedClients) {
        this.allowedClients = Arrays.stream(allowedClients.split(","))
            .map(String::trim)
            .filter(client -> !client.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
    }

    public boolean allowed(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            return false;
        }
        Object azp = token.getToken().getClaims().get("azp");
        return azp != null && allowedClients.contains(azp.toString());
    }
}
