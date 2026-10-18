package com.bank.customer;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fail closed: with every SERVICE_CALLERS* list emptied at install time, no
 * service reads or moves anything, not even the client each list is meant
 * for; staff still can. There is no fallback to the application defaults
 * once a value is set, and the chart refuses an unset or empty value
 * (requiredConfig), so an installation cannot silently open a route.
 */
@SpringBootTest(properties = {"customer.outbox.relay.enabled=false", "fintechbankx.tls.enforce=false",
    "fintechbankx.security.service-callers=", "fintechbankx.security.service-callers-kyc=",
    "fintechbankx.security.service-callers-credit-standing=", "fintechbankx.security.service-callers-screening="})
@AutoConfigureMockMvc
class EmptyAllowListsIT {

    @BeforeAll
    static void requireDatabase() {
        PostgresTestDatabase.assumeAvailable();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresTestDatabase.register(registry);
    }

    @Autowired MockMvc mvc;
    @MockBean KafkaTemplate<String, String> kafka;
    @MockBean org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder;

    @BeforeEach
    void cleanTables() {
        var owner = PostgresTestDatabase.owner();
        owner.update("delete from sc_cus_profile_kyc.outbox_event");
        owner.update("delete from sc_cus_profile_kyc.credit_movement");
        owner.update("delete from sc_cus_profile_kyc.credit_reservation");
        owner.update("delete from sc_cus_profile_kyc.customer");
    }

    @Test
    void everyEmptiedListDeniesTheClientItIsMeantFor() throws Exception {
        String customerId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
            .readTree(mvc.perform(asBanker(post("/api/v1/customers"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"firstName": "Noor", "lastName": "Rahman", "email": "empty-lists@example.com",
                     "phoneNumber": "+971500000123", "initialCreditLimit": 10000.00, "currency": "AED"}
                    """))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString()).get("customerId").asText();

        Map<String, String> routeByClient = Map.of(
            "svc-ln-loan-lifecycle", "/api/v1/customers/{id}/credit",
            "svc-pay-initiation-settlement", "/api/v1/customers/{id}/kyc-status",
            "svc-rsk-decisioning", "/api/v1/customers/{id}/credit-standing",
            "svc-cmp-evidence", "/api/v1/customers/{id}/screening-profile");
        for (var entry : routeByClient.entrySet()) {
            mvc.perform(asService(get(entry.getValue(), customerId), entry.getKey()))
                .andExpect(status().isForbidden());
            mvc.perform(asBanker(get(entry.getValue(), customerId)))
                .andExpect(status().isOk());
        }
        mvc.perform(asService(post("/api/v1/customers/{id}/credit/reserve", customerId), "svc-ln-loan-lifecycle")
                .header("x-idempotency-key", "empty-1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": 10.00, \"currency\": \"AED\"}"))
            .andExpect(status().isForbidden());
    }

    private static MockHttpServletRequestBuilder asBanker(MockHttpServletRequestBuilder request) {
        return request.header("x-fapi-interaction-id", "empty-lists-it")
            .with(jwt().jwt(j -> j.subject("banker-1")).authorities(new SimpleGrantedAuthority("ROLE_BANKER")));
    }

    private static MockHttpServletRequestBuilder asService(MockHttpServletRequestBuilder request, String azp) {
        return request.header("x-fapi-interaction-id", "empty-lists-it")
            .with(jwt().jwt(j -> j.subject("service-account-" + azp).claim("azp", azp))
                .authorities(new SimpleGrantedAuthority("ROLE_SERVICE")));
    }
}
