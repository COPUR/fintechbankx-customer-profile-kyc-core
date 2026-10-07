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

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "RS256").subject("user-1")
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        claims.forEach(builder::claim);
        return builder.build();
    }
}
