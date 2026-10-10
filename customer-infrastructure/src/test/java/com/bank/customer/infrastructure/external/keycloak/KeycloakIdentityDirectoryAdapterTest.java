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
    private final io.micrometer.core.instrument.simple.SimpleMeterRegistry meters =
        new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        keycloak = MockRestServiceServer.bindTo(builder).build();
        adapter = new KeycloakIdentityDirectoryAdapter(builder.build(),
            new KeycloakAdminSettings(BASE, "fintechbankx", "svc-cus-profile-kyc", "test-client-credential", Duration.ofSeconds(3)),
            Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC), meters);
    }

    /**
     * client_secret_basic (RFC 6749 section 2.3.1): the client id and secret travel only in the
     * Authorization header, form-urlencoded then base64; the form body is grant_type alone, so no
     * request-body log can ever hold the credential.
     */
    private void expectToken() {
        expectToken("svc-cus-profile-kyc", "test-client-credential");
    }

    private void expectToken(String clientId, String secret) {
        String basic = java.util.Base64.getEncoder().encodeToString(
            (java.net.URLEncoder.encode(clientId, java.nio.charset.StandardCharsets.UTF_8) + ":"
                + java.net.URLEncoder.encode(secret, java.nio.charset.StandardCharsets.UTF_8))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        keycloak.expect(once(), requestTo(TOKEN_URL))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Basic " + basic))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
            .andExpect(content().string("grant_type=client_credentials"))
            .andRespond(withSuccess("{\"access_token\":\"admin-token\",\"expires_in\":300}", MediaType.APPLICATION_JSON));
    }

    /** Reserved characters in the secret are form-encoded before base64, as Keycloak decodes them. */
    @Test
    void theClientCredentialIsSentInTheBasicHeaderFormEncoded() {
        RestClient.Builder builder = RestClient.builder();
        keycloak = MockRestServiceServer.bindTo(builder).build();
        adapter = new KeycloakIdentityDirectoryAdapter(builder.build(),
            new KeycloakAdminSettings(BASE, "fintechbankx", "svc-cus-profile-kyc", "a+b:c/d=e %f", Duration.ofSeconds(3)),
            Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC), meters);
        expectToken("svc-cus-profile-kyc", "a+b:c/d=e %f");
        keycloak.expect(once(), requestTo(USER_URL)).andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("{\"id\":\"" + USER + "\",\"attributes\":{\"customer_id\":[\"CUST-1A2B3C4D\"]}}",
                MediaType.APPLICATION_JSON));

        adapter.linkCustomer(USER_ID, CUSTOMER);

        keycloak.verify();
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

    /**
     * Platform ruling on linkCustomer (option a), key set from observability #11
     * e06915f: the PUT body is exactly username, email, firstName, lastName,
     * emailVerified and attributes, each the stored value from the GET, with
     * only customer_id added to the attributes. Never enabled, credentials,
     * requiredActions, federatedIdentities, realmRoles, clientRoles, groups,
     * access or anything else the GET returned: Keycloak leaves absent
     * top-level fields untouched, and the out-of-scope alert fires on them.
     */
    @Test
    void thePutBodyIsExactlyTheAllowedFieldsFromTheGetWithCustomerIdAddedToTheAttributes() throws Exception {
        String stored = "{\"id\":\"" + USER + "\",\"createdTimestamp\":1759900000000,\"username\":\"noor\","
            + "\"enabled\":false,\"totp\":false,\"emailVerified\":true,\"firstName\":\"Noor\",\"lastName\":\"Haddad\","
            + "\"email\":\"noor@example.com\",\"attributes\":{\"locale\":[\"ar\"],\"branch\":[\"DXB-1\"]},"
            + "\"disableableCredentialTypes\":[],\"requiredActions\":[\"UPDATE_PASSWORD\"],"
            + "\"credentials\":[{\"type\":\"password\"}],\"federatedIdentities\":[{\"identityProvider\":\"uaepass\"}],"
            + "\"realmRoles\":[\"CUSTOMER\"],\"clientRoles\":{\"app\":[\"x\"]},\"groups\":[\"/customers\"],"
            + "\"notBefore\":0,\"access\":{\"manage\":true}}";
        String expected = "{\"username\":\"noor\",\"email\":\"noor@example.com\",\"firstName\":\"Noor\","
            + "\"lastName\":\"Haddad\",\"emailVerified\":true,"
            + "\"attributes\":{\"locale\":[\"ar\"],\"branch\":[\"DXB-1\"],\"customer_id\":[\"CUST-1A2B3C4D\"]}}";
        java.util.concurrent.atomic.AtomicReference<String> sent = new java.util.concurrent.atomic.AtomicReference<>();
        expectToken();
        keycloak.expect(once(), requestTo(USER_URL)).andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(stored, MediaType.APPLICATION_JSON));
        keycloak.expect(once(), requestTo(USER_URL)).andExpect(method(HttpMethod.PUT))
            .andExpect(request -> sent.set(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString()))
            .andRespond(withStatus(HttpStatus.NO_CONTENT));

        adapter.linkCustomer(USER_ID, CUSTOMER);

        keycloak.verify();
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode body = json.readTree(sent.get());
        com.fasterxml.jackson.databind.JsonNode get = json.readTree(stored);
        assertThat(body.fieldNames()).toIterable().as("exact key set (observability #11 e06915f)")
            .containsExactlyInAnyOrder("username", "email", "firstName", "lastName", "emailVerified", "attributes");
        for (String root : java.util.List.of("username", "email", "firstName", "lastName", "emailVerified")) {
            assertThat(body.get(root)).as("%s unchanged from the GET", root).isEqualTo(get.get(root));
        }
        assertThat(body.has("enabled")).as("enabled is never sent").isFalse();
        assertThat(body.has("requiredActions")).isFalse();
        assertThat(body.has("credentials")).isFalse();
        assertThat(body).isEqualTo(json.readTree(expected));
    }

    /** A root field the GET did not return is left out (never sent as null), so Keycloak keeps it unchanged. */
    @Test
    void aRootFieldTheGetDidNotReturnIsNotSent() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> sent = new java.util.concurrent.atomic.AtomicReference<>();
        expectToken();
        keycloak.expect(once(), requestTo(USER_URL)).andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("{\"id\":\"" + USER + "\",\"username\":\"sara\",\"emailVerified\":false,"
                + "\"enabled\":true,\"requiredActions\":[]}", MediaType.APPLICATION_JSON));
        keycloak.expect(once(), requestTo(USER_URL)).andExpect(method(HttpMethod.PUT))
            .andExpect(request -> sent.set(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString()))
            .andRespond(withStatus(HttpStatus.NO_CONTENT));

        adapter.linkCustomer(USER_ID, CUSTOMER);

        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(sent.get())).isEqualTo(
            new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                "{\"username\":\"sara\",\"emailVerified\":false,\"attributes\":{\"customer_id\":[\"CUST-1A2B3C4D\"]}}"));
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

    /**
     * Keycloak FGAP v2 (identity 8f9024b) lets this client read and update
     * users in group /customers only; a 403 on reading the user means "not a
     * customer user", the same outcome as an unknown user, never a 503.
     */
    @Test
    void aUserOutsideTheCustomersGroupIsNotFound() {
        expectToken();
        keycloak.expect(once(), requestTo(USER_URL)).andExpect(method(HttpMethod.GET))
            .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> adapter.linkCustomer(USER_ID, CUSTOMER)).isInstanceOf(IdentityUserNotFoundException.class);
        keycloak.verify();
    }

    /**
     * Review 5459671617: a 403 stays a 422 for the caller but is observable:
     * identity.directory.responses{status} counts it, and a warning (without
     * the user id) says it is expected only for users outside /customers. If
     * every link answers 422 after enabling, the FGAP permission is missing.
     */
    @Test
    void forbiddenAndNotFoundReadsAreCountedAndTheForbiddenOneIsLoggedWithoutTheUserId() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(KeycloakIdentityDirectoryAdapter.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs =
            new ch.qos.logback.core.read.ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            expectToken();
            keycloak.expect(once(), requestTo(USER_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));
            keycloak.expect(once(), requestTo(USER_URL)).andRespond(withResourceNotFound());

            assertThatThrownBy(() -> adapter.linkCustomer(USER_ID, CUSTOMER)).isInstanceOf(IdentityUserNotFoundException.class);
            assertThatThrownBy(() -> adapter.linkCustomer(USER_ID, CUSTOMER)).isInstanceOf(IdentityUserNotFoundException.class);

            assertThat(meters.get("identity.directory.responses").tag("status", "403").counter().count()).isEqualTo(1.0);
            assertThat(meters.get("identity.directory.responses").tag("status", "404").counter().count()).isEqualTo(1.0);
            assertThat(logs.list).filteredOn(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
                .extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .containsExactly("Keycloak answered 403 reading an identity user; expected only for users outside /customers")
                .noneMatch(message -> message.contains(USER));
        } finally {
            logger.detachAppender(logs);
        }
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
