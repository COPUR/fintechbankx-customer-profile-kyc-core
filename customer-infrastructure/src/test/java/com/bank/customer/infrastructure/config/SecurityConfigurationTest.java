package com.bank.customer.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigurationTest {

    @Test
    void keycloakRealmRolesBecomeSpringRoles() {
        Jwt jwt = jwt(Map.of("realm_access", Map.of("roles", List.of("customer", "banker"))));

        assertThat(SecurityConfiguration.realmRoles(jwt)).extracting(GrantedAuthority::getAuthority)
            .containsExactly("ROLE_CUSTOMER", "ROLE_BANKER");
        assertThat(SecurityConfiguration.keycloakRealmRoles().convert(jwt).getName()).isEqualTo("user-1");
    }

    @Test
    void tokensWithoutRealmRolesGetNoAuthorities() {
        assertThat(SecurityConfiguration.realmRoles(jwt(Map.of("scope", "customer:read")))).isEmpty();
        assertThat(SecurityConfiguration.realmRoles(jwt(Map.of("realm_access", Map.of())))).isEmpty();
    }

    @Test
    void tokensMustNameThisServiceInTheirAudience() {
        var validator = SecurityConfiguration.audienceValidator("svc-cus-profile-kyc");

        assertThat(validator.validate(jwt(Map.of("aud", List.of("account", "svc-cus-profile-kyc")))).hasErrors()).isFalse();
        assertThat(validator.validate(jwt(Map.of("aud", List.of("loan-frontend")))).hasErrors()).isTrue();
        assertThat(validator.validate(jwt(Map.of("scope", "openid"))).hasErrors()).isTrue();
    }

    @Test
    void theDecoderUsesTheConfiguredKeySetWithoutCallingItAtStartup() {
        var properties = new org.springframework.boot.autoconfigure.security.oauth2.resource.OAuth2ResourceServerProperties();
        properties.getJwt().setIssuerUri("https://keycloak.example/realms/fintechbankx");
        properties.getJwt().setJwkSetUri("https://keycloak.example/realms/fintechbankx/protocol/openid-connect/certs");

        assertThat(new SecurityConfiguration().jwtDecoder(properties, "svc-cus-profile-kyc"))
            .isInstanceOf(org.springframework.security.oauth2.jwt.NimbusJwtDecoder.class);
    }

    @Test
    void anEmptyAudienceSettingStopsStartup() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> SecurityConfiguration.audienceValidator(" "))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void onlyListedClientsPassTheServiceCallerPolicy() {
        var policy = new ServiceCallerPolicy("svc-ln-loan-lifecycle, svc-pay-initiation-settlement");

        assertThat(policy.allowed(SecurityConfiguration.keycloakRealmRoles().convert(jwt(Map.of("azp", "svc-ln-loan-lifecycle"))))).isTrue();
        assertThat(policy.allowed(SecurityConfiguration.keycloakRealmRoles().convert(jwt(Map.of("azp", "svc-rsk-decisioning"))))).isFalse();
        assertThat(policy.allowed(SecurityConfiguration.keycloakRealmRoles().convert(jwt(Map.of())))).isFalse();
        assertThat(policy.allowed(null)).isFalse();
    }

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "RS256").subject("user-1")
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        claims.forEach(builder::claim);
        return builder.build();
    }
}
