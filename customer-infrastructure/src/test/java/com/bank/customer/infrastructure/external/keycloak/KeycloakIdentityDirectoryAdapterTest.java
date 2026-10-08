package com.bank.customer.infrastructure.external.keycloak;

import com.bank.customer.domain.IdentityDirectoryUnavailableException;
import com.bank.customer.domain.IdentityLinkConflictException;
import com.bank.customer.domain.IdentityUserId;
import com.bank.customer.domain.IdentityUserNotFoundException;
import com.bank.shared.kernel.domain.CustomerId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KeycloakIdentityDirectoryAdapterTest {

    private static final String BASE = "http://keycloak.test:8080";
    private static final String TOKEN_URL = BASE + "/realms/fintechbankx/protocol/openid-connect/token";
    private static final String USER = "6f1c2a7e-5b8d-4c3e-9a1f-0d2b3c4e5f60";
    private static final String USER_URL = BASE + "/admin/realms/fintechbankx/users/" + USER;
    private static final IdentityUserId USER_ID = new IdentityUserId(USER);
    private static final CustomerId CUSTOMER = CustomerId.of("CUST-1A2B3C4D");

    private MockRestServiceServer keycloak;
    private KeycloakIdentityDirectoryAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        keycloak = MockRestServiceServer.bindTo(builder).build();
        adapter = new KeycloakIdentityDirectoryAdapter(builder.build(),
            new KeycloakAdminSettings(BASE, "fintechbankx", "svc-cus-profile-kyc", "test-client-credential", Duration.ofSeconds(3)),
            Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC));
    }

    private void expectToken() {
        keycloak.expect(once(), requestTo(TOKEN_URL))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
            .andExpect(content().formDataContains(java.util.Map.of(
                "grant_type", "client_credentials", "client_id", "svc-cus-profile-kyc",
                "client_secret", "test-client-credential")))
            .andRespond(withSuccess("{\"access_token\":\"admin-token\",\"expires_in\":300}", MediaType.APPLICATION_JSON));
    }

    @Test
    void setsCustomerIdOnTheUserAndKeepsItsOtherAttributes() {
        expectToken();
        keycloak.expect(once(), requestTo(USER_URL)).andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer admin-token"))
            .andRespond(withSuccess("{\"id\":\"" + USER + "\",\"username\":\"noor\",\"enabled\":true,"
                + "\"attributes\":{\"locale\":[\"ar\"]}}", MediaType.APPLICATION_JSON));
        keycloak.expect(once(), requestTo(USER_URL)).andExpect(method(HttpMethod.PUT))
            .andExpect(header("Authorization", "Bearer admin-token"))
            .andExpect(jsonPath("$.attributes.customer_id[0]").value("CUST-1A2B3C4D"))
            .andExpect(jsonPath("$.attributes.locale[0]").value("ar"))
            .andExpect(jsonPath("$.username").value("noor"))
            .andRespond(withStatus(HttpStatus.NO_CONTENT));

        adapter.linkCustomer(USER_ID, CUSTOMER);

        keycloak.verify();
    }

    @Test
    void aUserThatAlreadyCarriesThisCustomerIdIsLeftAloneAndTheTokenIsReused() {
        expectToken();
        for (int i = 0; i < 2; i++) {
            keycloak.expect(once(), requestTo(USER_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"id\":\"" + USER + "\",\"attributes\":{\"customer_id\":[\"CUST-1A2B3C4D\"]}}",
                    MediaType.APPLICATION_JSON));
        }

        adapter.linkCustomer(USER_ID, CUSTOMER);
        adapter.linkCustomer(USER_ID, CUSTOMER);

        keycloak.verify();
    }

    @Test
    void aUserCarryingAnotherCustomerIdIsAConflictAndIsNotChanged() {
        expectToken();
        keycloak.expect(once(), requestTo(USER_URL)).andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("{\"id\":\"" + USER + "\",\"attributes\":{\"customer_id\":[\"CUST-OTHER001\"]}}",
                MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.linkCustomer(USER_ID, CUSTOMER)).isInstanceOf(IdentityLinkConflictException.class);
        keycloak.verify();
    }

    @Test
    void anUnknownUserIsNotFound() {
        expectToken();
        keycloak.expect(once(), requestTo(USER_URL)).andRespond(withResourceNotFound());

        assertThatThrownBy(() -> adapter.linkCustomer(USER_ID, CUSTOMER)).isInstanceOf(IdentityUserNotFoundException.class);
    }

    @Test
    void directoryErrorsAndRefusedCredentialsFailClosed() {
        keycloak.expect(once(), requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> adapter.linkCustomer(USER_ID, CUSTOMER))
            .isInstanceOf(IdentityDirectoryUnavailableException.class)
            .hasMessageNotContaining("test-client-credential");

        keycloak.reset();
        expectToken();
        keycloak.expect(once(), requestTo(USER_URL)).andRespond(withServerError());
        assertThatThrownBy(() -> adapter.linkCustomer(USER_ID, CUSTOMER))
            .isInstanceOf(IdentityDirectoryUnavailableException.class);

        keycloak.reset();
        // The token from the previous call is still valid and is reused.
        keycloak.expect(once(), requestTo(USER_URL)).andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("{\"id\":\"" + USER + "\"}", MediaType.APPLICATION_JSON));
        keycloak.expect(once(), requestTo(USER_URL)).andExpect(method(HttpMethod.PUT))
            .andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> adapter.linkCustomer(USER_ID, CUSTOMER))
            .isInstanceOf(IdentityDirectoryUnavailableException.class);
        keycloak.verify();
    }

    @Test
    void settingsMustBeComplete() {
        assertThatThrownBy(() -> new KeycloakAdminSettings("", "r", "c", "s", Duration.ofSeconds(1)))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new KeycloakAdminSettings(BASE, "r", "c", " ", Duration.ofSeconds(1)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageStartingWith("fintechbankx.identity.admin.client-secret must be set");
        assertThat(new KeycloakAdminSettings(BASE + "/", "r", "c", "s", null).baseUrl()).isEqualTo(BASE);
    }

    @Test
    void theDisabledDirectoryFailsClosed() {
        assertThatThrownBy(() -> new DisabledIdentityDirectory().linkCustomer(USER_ID, CUSTOMER))
            .isInstanceOf(IdentityDirectoryUnavailableException.class)
            .hasMessage("Identity directory integration is disabled (IDENTITY_ADMIN_ENABLED=false)");
    }
}
