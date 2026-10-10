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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Release by reference (loan PR #14 review), over HTTP against PostgreSQL.
 * A release naming a reservation's reference releases at most what that
 * reservation still holds (else 422 RELEASE_EXCEEDS_RESERVATION); a release
 * naming no known reservation releases at most the untracked used credit,
 * used credit minus all open reservations (else 422 RESERVATION_NOT_FOUND).
 * Idempotency-key replays answer as before.
 */
@SpringBootTest(properties = "customer.outbox.relay.enabled=false")
@AutoConfigureMockMvc
class CreditReservationIT {

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
        jdbc.update("delete from sc_cus_profile_kyc.credit_reservation");
        jdbc.update("delete from sc_cus_profile_kyc.customer");
    }

    @Test
    void partialReleasesTakeAtMostWhatTheReservationStillHolds() throws Exception {
        String customerId = create("res-partial@example.com");
        assertPosition(move("reserve", customerId, "LOAN-1:reserve", "3000.00", "LOAN-1"), "3000.00");

        assertPosition(move("release", customerId, "LOAN-1:part-1", "1000.00", "LOAN-1"), "2000.00");
        assertRefused(move("release", customerId, "LOAN-1:part-2", "2000.01", "LOAN-1"), "RELEASE_EXCEEDS_RESERVATION");
        assertUsed(customerId, "2000.00");
        assertPosition(move("release", customerId, "LOAN-1:part-3", "2000.00", "LOAN-1"), "0.00");
        assertRefused(move("release", customerId, "LOAN-1:part-4", "0.01", "LOAN-1"), "RELEASE_EXCEEDS_RESERVATION");

        // A replay of an applied release still answers 200 with the current position and moves nothing.
        assertPosition(move("release", customerId, "LOAN-1:part-1", "1000.00", "LOAN-1"), "0.00");
        assertThat(jdbc.queryForObject("select count(*) from sc_cus_profile_kyc.credit_movement where customer_id = ?",
            Integer.class, customerId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from sc_cus_profile_kyc.outbox_event where aggregate_id = ? "
            + "and event_type = 'Customer.Customer.CreditReleased.v1'", Integer.class, customerId)).isEqualTo(2);
    }

    /**
     * Loan auto-release: a release that carries a reference matching no
     * reservation is always 422 RESERVATION_NOT_FOUND, even when untracked
     * credit would cover it. Only a release with no reference takes untracked
     * credit, and never what another reservation holds.
     */
    @Test
    void aReleaseCarryingAnUnknownReferenceIsAlwaysRefusedAndOnlyAnUnreferencedOneTakesUntrackedCredit() throws Exception {
        String customerId = create("res-untracked@example.com");
        assertPosition(move("reserve", customerId, "UNTRACKED:reserve", "2000.00", null), "2000.00");
        assertPosition(move("reserve", customerId, "LOAN-A:reserve", "3000.00", "LOAN-A"), "5000.00");

        assertRefused(move("release", customerId, "LOAN-X:release-cent", "0.01", "LOAN-X"), "RESERVATION_NOT_FOUND");
        assertRefused(move("release", customerId, "LOAN-X:release", "2000.00", "LOAN-X"), "RESERVATION_NOT_FOUND");
        assertRefused(move("release", customerId, "NOREF:release-1", "2000.01", null), "RESERVATION_NOT_FOUND");
        assertUsed(customerId, "5000.00");

        assertPosition(move("release", customerId, "NOREF:release-ok", "2000.00", null), "3000.00");
        assertRefused(move("release", customerId, "NOREF:release-2", "0.01", null), "RESERVATION_NOT_FOUND");
        assertPosition(move("release", customerId, "LOAN-A:release", "3000.00", "LOAN-A"), "0.00");
        assertThat(jdbc.queryForObject("select count(*) from sc_cus_profile_kyc.credit_reservation "
            + "where customer_id = ? and reference = 'LOAN-X'", Integer.class, customerId)).isZero();
    }

    /** Parity CU-09 changes on purpose: releasing more than is used is refused, not floored at zero. */
    @Test
    void releasingMoreThanIsUsedIsRefusedInsteadOfFlooredAtZero() throws Exception {
        String customerId = create("res-cu09@example.com");
        assertPosition(move("reserve", customerId, "CU09:reserve", "500.00", null), "500.00");

        assertRefused(move("release", customerId, "CU09:release-more", "1000.00", null), "RESERVATION_NOT_FOUND");

        assertUsed(customerId, "500.00");
    }

    /**
     * A balance migrated from the monolith has no reservation: an unknown
     * loan id cannot release it, a release with no reference can.
     */
    @Test
    void aMigratedBalanceIsReleasedOnlyByAReleaseWithoutAReference() throws Exception {
        String customerId = create("res-migrated@example.com");
        jdbc.update("update sc_cus_profile_kyc.customer set used_credit = 4000.00, version = version + 1 where customer_id = ?",
            customerId);

        assertRefused(move("release", customerId, "LEGACY-1:release", "4000.00", "LEGACY-1"), "RESERVATION_NOT_FOUND");
        assertUsed(customerId, "4000.00");
        assertPosition(move("release", customerId, "LEGACY:release", "4000.00", null), "0.00");
        assertThat(jdbc.queryForObject("select count(*) from sc_cus_profile_kyc.credit_reservation where customer_id = ?",
            Integer.class, customerId)).isZero();
    }

    private void assertPosition(MockHttpServletResponse response, String used) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        JsonNode body = json.readTree(response.getContentAsString());
        assertThat(body.get("usedCredit").decimalValue()).isEqualByComparingTo(new BigDecimal(used));
    }

    private void assertRefused(MockHttpServletResponse response, String code) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(422);
        JsonNode body = json.readTree(response.getContentAsString());
        assertThat(body.get("code").asText()).isEqualTo(code);
        assertThat(body.get("message").asText()).doesNotContainPattern("\\d{3,}");
    }

    private void assertUsed(String customerId, String used) {
        assertThat(jdbc.queryForObject("select used_credit from sc_cus_profile_kyc.customer where customer_id = ?",
            BigDecimal.class, customerId)).isEqualByComparingTo(used);
    }

    private MockHttpServletResponse move(String movement, String customerId, String key, String amount, String reference)
            throws Exception {
        String body = reference == null
            ? "{\"amount\":%s,\"currency\":\"AED\"}".formatted(amount)
            : "{\"amount\":%s,\"currency\":\"AED\",\"reference\":\"%s\"}".formatted(amount, reference);
        return mvc.perform(asLoanService(post("/api/v1/customers/{id}/credit/" + movement, customerId))
                .header("x-idempotency-key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andReturn().getResponse();
    }

    private String create(String email) throws Exception {
        String body = mvc.perform(post("/api/v1/customers")
                .header("x-fapi-interaction-id", "reservation-banker")
                .with(jwt().jwt(j -> j.subject("banker-1")).authorities(new SimpleGrantedAuthority("ROLE_BANKER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"firstName": "Noor", "lastName": "Rahman", "email": "%s", "phoneNumber": "+971500000123",
                     "initialCreditLimit": 10000.00, "currency": "AED"}
                    """.formatted(email)))
            .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("customerId").asText();
    }

    private static MockHttpServletRequestBuilder asLoanService(MockHttpServletRequestBuilder request) {
        return request.header("x-fapi-interaction-id", "reservation-loan")
            .with(jwt().jwt(j -> j.subject("service-account-svc-ln-loan-lifecycle").claim("azp", "svc-ln-loan-lifecycle"))
                .authorities(new SimpleGrantedAuthority("ROLE_SERVICE")));
    }
}
