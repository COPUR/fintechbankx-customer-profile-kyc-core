package com.bank.customer.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.security.oauth2.resource.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Stateless OAuth2 resource server. Enforced here: the token is issued by the
 * platform Keycloak realm and names this service in its audience (aud, set by
 * a Keycloak audience mapper on each calling client), so a token minted for
 * another client is refused; SERVICE-role callers must have their client id
 * (azp) on the allow-list of the endpoint group ({@link ServiceCallerPolicy}:
 * SERVICE_CALLERS for credit, SERVICE_CALLERS_KYC for the KYC status). Realm
 * roles become ROLE_* authorities for the @PreAuthorize rules on
 * CustomerController.
 *
 * Mesh policy is not in this repository: the service-mesh repository owns the
 * STRICT mTLS and the per-caller ALLOW AuthorizationPolicy rules for namespace
 * customer. This service's chart ships only a NetworkPolicy backstop.
 *
 * DPoP is not verified here. Per the platform contract (addendum 2026-10-08)
 * DPoP binding applies only to open-finance TPP clients; this service is
 * called with internal client-credentials, staff and first-party web/mobile
 * tokens, which are not DPoP-bound. The OpenAPI DPoP header is therefore
 * optional.
 *
 * Actuator endpoints are served on the management port, which the chart's
 * NetworkPolicy opens to the monitoring namespace only.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll())
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(keycloakRealmRoles())));
        return http.build();
    }

    /** Clients (azp) that may read and move credit with the SERVICE role: the loan service. */
    @Bean("serviceCallers")
    ServiceCallerPolicy serviceCallers(@Value("${fintechbankx.security.service-callers}") String clients) {
        return new ServiceCallerPolicy(clients);
    }

    /** Clients (azp) that may read the KYC status with the SERVICE role: the payment service. */
    @Bean("kycServiceCallers")
    ServiceCallerPolicy kycServiceCallers(@Value("${fintechbankx.security.service-callers-kyc}") String clients) {
        return new ServiceCallerPolicy(clients);
    }

    @Bean
    JwtDecoder jwtDecoder(OAuth2ResourceServerProperties properties,
                          @Value("${fintechbankx.security.audience}") String audience) {
        OAuth2ResourceServerProperties.Jwt jwt = properties.getJwt();
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwt.getJwkSetUri()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(jwt.getIssuerUri()),
            audienceValidator(audience)));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> audienceValidator(String audience) {
        if (audience == null || audience.isBlank()) {
            throw new IllegalStateException("fintechbankx.security.audience must name this service's OAuth2 client");
        }
        OAuth2Error wrongAudience = new OAuth2Error("invalid_token", "The token is not issued for " + audience, null);
        return token -> token.getAudience() != null && token.getAudience().contains(audience)
            ? OAuth2TokenValidatorResult.success()
            : OAuth2TokenValidatorResult.failure(wrongAudience);
    }

    /** Access-token claim with the end user's customer profile id (platform contract, "End-user and caller claims"). */
    static final String CUSTOMER_ID_CLAIM = "customer_id";

    /**
     * Realm roles become authorities. The principal name is the customer_id
     * claim when the token has one (end-user tokens, whose subject is the
     * Keycloak user UUID), otherwise the subject (staff and service tokens),
     * so the ownership rules can compare the path id with authentication.name.
     */
    static Converter<Jwt, AbstractAuthenticationToken> keycloakRealmRoles() {
        return jwt -> new JwtAuthenticationToken(jwt, realmRoles(jwt), principalName(jwt));
    }

    static String principalName(Jwt jwt) {
        String customerId = jwt.getClaimAsString(CUSTOMER_ID_CLAIM);
        return customerId == null || customerId.isBlank() ? jwt.getSubject() : customerId;
    }

    @SuppressWarnings("unchecked")
    static Collection<GrantedAuthority> realmRoles(Jwt jwt) {
        Object realmAccess = jwt.getClaims().get("realm_access");
        if (!(realmAccess instanceof Map<?, ?> access) || !(access.get("roles") instanceof Collection<?> roles)) {
            return List.of();
        }
        return ((Collection<Object>) roles).stream()
            .map(String::valueOf)
            .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()))
            .toList();
    }
}
