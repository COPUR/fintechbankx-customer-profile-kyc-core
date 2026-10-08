package com.bank.customer.infrastructure.external.keycloak;

import java.time.Duration;

/**
 * Where and as whom the service calls the Keycloak admin API. The client is
 * this service's own confidential client (client credentials); its service
 * account needs the realm-management role manage-users (or a fine-grained
 * permission limited to the customer_id attribute). The credential comes from
 * the environment (Secrets Manager, &lt;env&gt;/customer-profile-kyc-service/oidc-client).
 */
public record KeycloakAdminSettings(String baseUrl, String realm, String clientId, String clientSecret,
                                    Duration timeout) {

    public KeycloakAdminSettings {
        requireText(baseUrl, "fintechbankx.identity.admin.base-url");
        requireText(realm, "fintechbankx.identity.admin.realm");
        requireText(clientId, "fintechbankx.identity.admin.client-id");
        requireText(clientSecret, "fintechbankx.identity.admin.client-secret");
        baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        timeout = timeout == null ? Duration.ofSeconds(3) : timeout;
    }

    private static void requireText(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " must be set when the identity directory integration is enabled");
        }
    }

    String tokenUrl() {
        return baseUrl + "/realms/" + realm + "/protocol/openid-connect/token";
    }

    String userUrl() {
        return baseUrl + "/admin/realms/" + realm + "/users/{userId}";
    }
}
