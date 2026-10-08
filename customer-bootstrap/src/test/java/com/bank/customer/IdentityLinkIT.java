package com.bank.customer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Onboarding links a customer to the end user's Keycloak account through the
 * real Keycloak admin adapter, against a stand-in Keycloak (token endpoint and
 * users resource) on a local port, so the customer_id user attribute ends up
 * on the user the way the platform realm expects it.
 */
@SpringBootTest(properties = "customer.outbox.relay.enabled=false")
@AutoConfigureMockMvc
class IdentityLinkIT {

    private static final String REALM = "fintechbankx";
    private static final String USER = "6f1c2a7e-5b8d-4c3e-9a1f-0d2b3c4e5f60";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, ObjectNode> USERS = new ConcurrentHashMap<>();
    private static final List<String> REQUESTS = new CopyOnWriteArrayList<>();
    private static HttpServer keycloak;

    @BeforeAll
    static void start() throws IOException {
        PostgresTestDatabase.assumeAvailable();
        keycloak = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        keycloak.createContext("/realms/" + REALM + "/protocol/openid-connect/token", exchange -> {
            String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            REQUESTS.add("POST token " + (form.contains("grant_type=client_credentials") && form.contains("client_id=svc-cus-profile-kyc")));
            respond(exchange, 200, "{\"access_token\":\"admin-token\",\"expires_in\":300}");
        });
        keycloak.createContext("/admin/realms/" + REALM + "/users/", exchange -> {
            String id = exchange.getRequestURI().getPath().substring(("/admin/realms/" + REALM + "/users/").length());
            boolean authorised = "Bearer admin-token".equals(exchange.getRequestHeaders().getFirst("Authorization"));
            REQUESTS.add(exchange.getRequestMethod() + " user " + id + " " + authorised);
            ObjectNode user = USERS.get(id);
            if (!authorised) {
                respond(exchange, 401, "");
            } else if (user == null) {
                respond(exchange, 404, "{\"error\":\"User not found\"}");
            } else if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, user.toString());
            } else if ("PUT".equals(exchange.getRequestMethod())) {
                USERS.put(id, (ObjectNode) JSON.readTree(exchange.getRequestBody()));
                respond(exchange, 204, "");
            } else {
                respond(exchange, 405, "");
            }
        });
        keycloak.start();
    }

    @AfterAll
    static void stop() {
        if (keycloak != null) {
            keycloak.stop(0);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresTestDatabase.register(registry);
        registry.add("fintechbankx.identity.admin.enabled", () -> "true");
        registry.add("fintechbankx.identity.admin.base-url", () -> "http://127.0.0.1:" + keycloak.getAddress().getPort());
        registry.add("fintechbankx.identity.admin.realm", () -> REALM);
        registry.add("fintechbankx.identity.admin.client-secret", () -> "it-client-credential");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockBean KafkaTemplate<String, String> kafka;

    @BeforeEach
    void reset() throws IOException {
        jdbc.update("delete from sc_cus_profile_kyc.outbox_event");
        jdbc.update("delete from sc_cus_profile_kyc.credit_movement");
        jdbc.update("delete from sc_cus_profile_kyc.customer");
        USERS.clear();
        USERS.put(USER, (ObjectNode) JSON.readTree("{\"id\":\"" + USER + "\",\"username\":\"noor\",\"attributes\":{\"locale\":[\"ar\"]}}"));
        REQUESTS.clear();
    }

    @Test
    void onboardingSetsTheCustomerIdAttributeOnTheLinkedUser() throws Exception {
        String customerId = create("link@example.com");

        link(customerId, USER).andExpect(status().isOk())
            .andExpect(jsonPath("$.customerId").value(customerId))
            .andExpect(jsonPath("$.identityUserId").value(USER));

        assertThat(USERS.get(USER).at("/attributes/customer_id/0").asText()).isEqualTo(customerId);
        assertThat(USERS.get(USER).at("/attributes/locale/0").asText()).isEqualTo("ar");
        assertThat(jdbc.queryForObject("select identity_user_id from sc_cus_profile_kyc.customer where customer_id = ?",
            String.class, customerId)).isEqualTo(USER);
        assertThat(REQUESTS).containsExactly("POST token true", "GET user " + USER + " true", "PUT user " + USER + " true");

        link(customerId, USER).andExpect(status().isOk());
        assertThat(REQUESTS).as("relinking only reads the user").hasSize(4).last().isEqualTo("GET user " + USER + " true");
    }

    @Test
    void oneIdentityUserCannotBeLinkedToTwoCustomers() throws Exception {
        String first = create("first@example.com");
        String second = create("second@example.com");
        link(first, USER).andExpect(status().isOk());
        REQUESTS.clear();

        link(second, USER).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDENTITY_LINK_CONFLICT"));

        assertThat(REQUESTS).as("refused before Keycloak is called").isEmpty();
        assertThat(jdbc.queryForObject("select identity_user_id from sc_cus_profile_kyc.customer where customer_id = ?",
            String.class, second)).isNull();
    }

    @Test
    void anUnknownKeycloakUserLeavesNoLink() throws Exception {
        String customerId = create("ghost@example.com");

        link(customerId, "no-such-user").andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("IDENTITY_USER_NOT_FOUND"));

        assertThat(jdbc.queryForObject("select identity_user_id from sc_cus_profile_kyc.customer where customer_id = ?",
            String.class, customerId)).as("rolled back").isNull();
    }

    @Test
    void onlyStaffMayLinkAndTheUserIdMustBeSafe() throws Exception {
        String customerId = create("staff@example.com");

        mvc.perform(put("/api/v1/customers/{id}/identity-link", customerId)
                .with(jwt().jwt(j -> j.subject(USER).claim("customer_id", customerId))
                    .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"identityUserId\": \"" + USER + "\"}"))
            .andExpect(status().isForbidden());
        link(customerId, "../../users").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertThat(REQUESTS).isEmpty();
    }

    private ResultActions link(String customerId, String userId) throws Exception {
        return mvc.perform(put("/api/v1/customers/{id}/identity-link", customerId)
            .with(jwt().jwt(j -> j.subject("banker-1")).authorities(new SimpleGrantedAuthority("ROLE_BANKER")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"identityUserId\": \"" + userId + "\"}"));
    }

    private String create(String email) throws Exception {
        String body = mvc.perform(post("/api/v1/customers")
                .with(jwt().jwt(j -> j.subject("banker-1")).authorities(new SimpleGrantedAuthority("ROLE_BANKER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"firstName": "Noor", "lastName": "Rahman", "email": "%s", "initialCreditLimit": 5000, "currency": "AED"}
                    """.formatted(email)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("customerId").asText();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, status == 204 || bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0 && status != 204) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}
