package com.bank.customer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KYC status: payments (svc-pay-initiation-settlement, its own allow-list
 * SERVICE_CALLERS_KYC) reads it without personal data; staff verify or
 * reject; the change is recorded with the staff subject and published
 * through the outbox. The loan service's credit allow-list does not open it,
 * and the KYC allow-list does not open credit.
 */
@SpringBootTest(properties = "customer.outbox.relay.enabled=false")
@AutoConfigureMockMvc
class KycStatusIT {

    @BeforeAll
    static void requireDatabase() {
        PostgresTestDatabase.assumeAvailable();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresTestDatabase.register(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @MockBean KafkaTemplate<String, String> kafka;
    @MockBean org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder;

    @BeforeEach
    void cleanTables() {
        jdbc.update("delete from sc_cus_profile_kyc.outbox_event");
        jdbc.update("delete from sc_cus_profile_kyc.credit_movement");
        jdbc.update("delete from sc_cus_profile_kyc.customer");
    }

    @Test
    void paymentsReadsTheKycFieldsOnly() throws Exception {
        String customerId = create("kyc-read@example.com");

        String body = mvc.perform(asService(get("/api/v1/customers/{id}/kyc-status", customerId), "svc-pay-initiation-settlement"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        JsonNode kyc = json.readTree(body);
        assertThat(kyc.fieldNames()).toIterable()
            .containsExactlyInAnyOrder("customerId", "kycVerified", "kycStatus", "kycSource", "verifiedAt");
        assertThat(kyc.get("customerId").asText()).isEqualTo(customerId);
        assertThat(kyc.get("kycVerified").asBoolean()).isFalse();
        assertThat(kyc.get("kycStatus").asText()).isEqualTo("PENDING");
        assertThat(kyc.get("kycSource").asText()).isEqualTo("STAFF");
        assertThat(kyc.get("verifiedAt").isNull()).isTrue();
    }

    @Test
    void eachServiceAllowListOpensOnlyItsOwnEndpoints() throws Exception {
        String customerId = create("kyc-callers@example.com");

        mvc.perform(asService(get("/api/v1/customers/{id}/kyc-status", customerId), "svc-ln-loan-lifecycle"))
            .andExpect(status().isForbidden());
        mvc.perform(asService(get("/api/v1/customers/{id}/kyc-status", customerId), "svc-rsk-decisioning"))
            .andExpect(status().isForbidden());
        mvc.perform(asService(get("/api/v1/customers/{id}/credit", customerId), "svc-pay-initiation-settlement"))
            .andExpect(status().isForbidden());
        mvc.perform(asService(get("/api/v1/customers/{id}", customerId), "svc-pay-initiation-settlement"))
            .andExpect(status().isForbidden());
        mvc.perform(asService(put("/api/v1/customers/{id}/kyc-status", customerId), "svc-pay-initiation-settlement")
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\": \"VERIFIED\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void staffAndTheCustomerThemselvesMayReadIt() throws Exception {
        String customerId = create("kyc-own@example.com");

        mvc.perform(asRole(get("/api/v1/customers/{id}/kyc-status", customerId), "staff-1", "BANKER")).andExpect(status().isOk());
        mvc.perform(asRole(get("/api/v1/customers/{id}/kyc-status", customerId), "staff-2", "ADMIN")).andExpect(status().isOk());
        mvc.perform(asRole(get("/api/v1/customers/{id}/kyc-status", customerId), customerId, "CUSTOMER")).andExpect(status().isOk());
        mvc.perform(asRole(get("/api/v1/customers/{id}/kyc-status", customerId), "CUST-OTHER", "CUSTOMER")).andExpect(status().isForbidden());
        mvc.perform(asRole(get("/api/v1/customers/{id}/kyc-status", "CUST-00000000"), "staff-1", "BANKER"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"));
        mvc.perform(asService(get("/api/v1/customers/{id}/kyc-status", "CUST-00000000"), "svc-pay-initiation-settlement"))
            .andExpect(status().isNotFound());
    }

    @Test
    void staffVerifyRecordsTheirSubjectAndPublishesTheChange() throws Exception {
        String customerId = create("kyc-verify@example.com");

        mvc.perform(asRole(put("/api/v1/customers/{id}/kyc-status", customerId), "banker-sub-7", "BANKER")
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\": \"VERIFIED\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.kycVerified").value(true))
            .andExpect(jsonPath("$.kycStatus").value("VERIFIED"))
            .andExpect(jsonPath("$.kycSource").value("STAFF"))
            .andExpect(jsonPath("$.verifiedAt").isNotEmpty());

        Map<String, Object> row = jdbc.queryForMap("select kyc_status, kyc_source, kyc_verified_at, kyc_updated_by "
            + "from sc_cus_profile_kyc.customer where customer_id = ?", customerId);
        assertThat(row).containsEntry("kyc_status", "VERIFIED").containsEntry("kyc_source", "STAFF")
            .containsEntry("kyc_updated_by", "banker-sub-7");
        assertThat(row.get("kyc_verified_at")).isNotNull();
        Map<String, Object> event = jdbc.queryForMap("select topic, payload::text as payload from sc_cus_profile_kyc.outbox_event "
            + "where aggregate_id = ? and event_type = 'Customer.Customer.KycStatusChanged.v1'", customerId);
        assertThat(event).containsEntry("topic", "evt.cus.customer.kyc-status-changed.v1");
        assertThat((String) event.get("payload")).contains("\"kycStatus\": \"VERIFIED\"")
            .doesNotContain("banker-sub-7").doesNotContain("kyc-verify@example.com");

        mvc.perform(asRole(put("/api/v1/customers/{id}/kyc-status", customerId), "admin-sub-1", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\": \"REJECTED\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.kycVerified").value(false))
            .andExpect(jsonPath("$.verifiedAt").doesNotExist());
        mvc.perform(asService(get("/api/v1/customers/{id}/kyc-status", customerId), "svc-pay-initiation-settlement"))
            .andExpect(jsonPath("$.kycStatus").value("REJECTED"));
    }

    @Test
    void onlyStaffSetVerifiedOrRejected() throws Exception {
        String customerId = create("kyc-invalid@example.com");

        for (String body : new String[] {"{\"status\": \"PENDING\"}", "{\"status\": \"APPROVED\"}", "{}"}) {
            mvc.perform(asRole(put("/api/v1/customers/{id}/kyc-status", customerId), "banker-sub-7", "BANKER")
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        mvc.perform(asRole(put("/api/v1/customers/{id}/kyc-status", customerId), customerId, "CUSTOMER")
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\": \"VERIFIED\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(asRole(put("/api/v1/customers/{id}/kyc-status", "CUST-00000000"), "banker-sub-7", "BANKER")
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\": \"VERIFIED\"}"))
            .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select kyc_status from sc_cus_profile_kyc.customer where customer_id = ?",
            String.class, customerId)).isEqualTo("PENDING");
    }

    private String create(String email) throws Exception {
        String body = mvc.perform(asRole(post("/api/v1/customers"), "banker-1", "BANKER")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"firstName": "Noor", "lastName": "Rahman", "email": "%s", "phoneNumber": "+971500000123",
                     "initialCreditLimit": 10000.00, "currency": "AED"}
                    """.formatted(email)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("customerId").asText();
    }

    private static MockHttpServletRequestBuilder asRole(MockHttpServletRequestBuilder request, String subject, String role) {
        return request.header("x-fapi-interaction-id", "kyc-it")
            .with(jwt().jwt(j -> j.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private static MockHttpServletRequestBuilder asService(MockHttpServletRequestBuilder request, String azp) {
        return request.header("x-fapi-interaction-id", "kyc-it-service")
            .with(jwt().jwt(j -> j.subject("service-account-" + azp).claim("azp", azp))
                .authorities(new SimpleGrantedAuthority("ROLE_SERVICE")));
    }
}
