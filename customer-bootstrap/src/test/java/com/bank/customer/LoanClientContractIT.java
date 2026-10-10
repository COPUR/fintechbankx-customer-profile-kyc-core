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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Provider side of the svc-ln-loan-lifecycle -> svc-cus-profile-kyc contract.
 * Replays the requests the loan service's CustomerProfileHttpAdapter sends
 * (loan PR #14) and checks the answers it parses:
 *
 * <ul>
 *   <li>GET /credit, POST /credit/reserve and /credit/release with a
 *   client-credentials token (SERVICE role, azp svc-ln-loan-lifecycle), the
 *   x-fapi-interaction-id header and a deterministic x-idempotency-key derived
 *   from the loan: {@code <loanId>:reserve} ({@code :g<n>} after n cancelled
 *   reservations), {@code <loanId>:release}, {@code <reserveKey>:compensation};
 *   body {amount, currency, reference = loanId};</li>
 *   <li>200 bodies: customerId, creditLimit, usedCredit, availableCredit
 *   (numbers), currency (ISO code);</li>
 *   <li>error bodies carry {@code "code": "<UPPER_CASE>"}, which the adapter
 *   reads with a regex: 422 INSUFFICIENT_CREDIT (refused), 422
 *   CURRENCY_MISMATCH, 404 for an unknown customer, 409
 *   CONCURRENT_UPDATE/DUPLICATE_REQUEST (retried with the same key).</li>
 * </ul>
 */
@SpringBootTest(properties = "customer.outbox.relay.enabled=false")
@AutoConfigureMockMvc
class LoanClientContractIT {

    /** The adapter's own pattern for the error code (CustomerProfileHttpAdapter.ERROR_CODE). */
    private static final Pattern ERROR_CODE = Pattern.compile("\"code\"\\s*:\\s*\"([A-Z_]+)\"");

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
    void theCreditPositionHasTheFieldsTheLoanAdapterReads() throws Exception {
        String customerId = create("contract-position@example.com");

        JsonNode position = ok(mvc.perform(asLoanService(get("/api/v1/customers/{id}/credit", customerId)))
            .andReturn().getResponse());

        assertCreditPosition(position, customerId, "10000.00", "0", "10000.00");
    }

    @Test
    void aLoanReservesReplaysCompensatesAndReleasesWithKeysDerivedFromTheLoan() throws Exception {
        String customerId = create("contract-moves@example.com");

        assertCreditPosition(ok(move("reserve", customerId, "LOAN-77:reserve", "2500.00", "AED", "LOAN-77")),
            customerId, "10000.00", "2500.00", "7500.00");
        // A retry after a timeout resends the same key and body: same answer, credit moved once.
        assertCreditPosition(ok(move("reserve", customerId, "LOAN-77:reserve", "2500.00", "AED", "LOAN-77")),
            customerId, "10000.00", "2500.00", "7500.00");
        // Storing the disbursement failed: the adapter cancels with <reserveKey>:compensation ...
        assertCreditPosition(ok(move("release", customerId, "LOAN-77:reserve:compensation", "2500.00", "AED", "LOAN-77")),
            customerId, "10000.00", "0", "10000.00");
        // ... and the next attempt reserves under the next generation.
        assertCreditPosition(ok(move("reserve", customerId, "LOAN-77:reserve:g1", "2500.00", "AED", "LOAN-77")),
            customerId, "10000.00", "2500.00", "7500.00");
        // Loan repaid.
        assertCreditPosition(ok(move("release", customerId, "LOAN-77:release", "2500.00", "AED", "LOAN-77")),
            customerId, "10000.00", "0", "10000.00");

        assertThat(jdbc.queryForList("select idempotency_key || ' ' || reference from sc_cus_profile_kyc.credit_movement "
                + "where customer_id = ? order by occurred_at, idempotency_key", String.class, customerId))
            .containsExactlyInAnyOrder("LOAN-77:reserve LOAN-77", "LOAN-77:reserve:compensation LOAN-77",
                "LOAN-77:reserve:g1 LOAN-77", "LOAN-77:release LOAN-77");
        // Both generations are netted on the loan's one reservation row.
        assertThat(jdbc.queryForMap("select reserved_amount, released_amount from sc_cus_profile_kyc.credit_reservation "
                + "where customer_id = ? and reference = 'LOAN-77'", customerId))
            .satisfies(row -> {
                assertThat((BigDecimal) row.get("reserved_amount")).isEqualByComparingTo("5000.00");
                assertThat((BigDecimal) row.get("released_amount")).isEqualByComparingTo("5000.00");
            });
    }

    /** Loan PR #14: a cancel releases at most what the loan reserved, never another loan's credit. */
    @Test
    void aReleaseIsBoundedByTheLoansReservation() throws Exception {
        String customerId = create("contract-bounded@example.com");
        ok(move("reserve", customerId, "LOAN-81:reserve", "2500.00", "AED", "LOAN-81"));
        ok(move("reserve", customerId, "LOAN-82:reserve", "1000.00", "AED", "LOAN-82"));

        assertError(move("release", customerId, "LOAN-81:reserve:compensation", "2500.01", "AED", "LOAN-81"),
            422, "RELEASE_EXCEEDS_RESERVATION");
        // A loan this service never reserved for: always refused, whatever untracked credit exists.
        assertError(move("release", customerId, "LOAN-83:release", "100.00", "AED", "LOAN-83"),
            422, "RESERVATION_NOT_FOUND");
        assertCreditPosition(ok(move("release", customerId, "LOAN-81:reserve:compensation", "2500.00", "AED", "LOAN-81")),
            customerId, "10000.00", "1000.00", "9000.00");
        // Fully released: the reservation holds nothing any more, so the loan's sweep reads "nothing left"
        // (RESERVATION_NOT_FOUND), not "released too much", whatever the amount.
        assertError(move("release", customerId, "LOAN-81:release", "0.01", "AED", "LOAN-81"),
            422, "RESERVATION_NOT_FOUND");
        assertError(move("release", customerId, "LOAN-82:release", "1000.01", "AED", "LOAN-82"),
            422, "RELEASE_EXCEEDS_RESERVATION");
    }

    @Test
    void refusalsCarryTheCodesTheLoanAdapterMaps() throws Exception {
        String customerId = create("contract-errors@example.com");

        assertError(move("reserve", customerId, "LOAN-78:reserve", "10000.01", "AED", "LOAN-78"),
            422, "INSUFFICIENT_CREDIT");
        assertError(move("reserve", customerId, "LOAN-79:reserve", "100.00", "USD", "LOAN-79"),
            422, "CURRENCY_MISMATCH");
        assertError(move("reserve", "CUST-00000000", "LOAN-80:reserve", "100.00", "AED", "LOAN-80"),
            404, "CUSTOMER_NOT_FOUND");
        assertError(mvc.perform(asLoanService(get("/api/v1/customers/{id}/credit", "CUST-00000000")))
            .andReturn().getResponse(), 404, "CUSTOMER_NOT_FOUND");
        assertThat(jdbc.queryForObject("select count(*) from sc_cus_profile_kyc.credit_movement", Integer.class)).isZero();
    }

    private void assertCreditPosition(JsonNode position, String customerId, String limit, String used, String available) {
        assertThat(position.fieldNames()).toIterable()
            .containsExactlyInAnyOrder("customerId", "currency", "creditLimit", "usedCredit", "availableCredit");
        assertThat(position.get("customerId").asText()).isEqualTo(customerId);
        assertThat(position.get("currency").asText()).isEqualTo("AED");
        for (String field : new String[] {"creditLimit", "usedCredit", "availableCredit"}) {
            assertThat(position.get(field).isNumber()).as(field + " is a JSON number").isTrue();
        }
        assertThat(position.get("creditLimit").decimalValue()).isEqualByComparingTo(new BigDecimal(limit));
        assertThat(position.get("usedCredit").decimalValue()).isEqualByComparingTo(new BigDecimal(used));
        assertThat(position.get("availableCredit").decimalValue()).isEqualByComparingTo(new BigDecimal(available));
    }

    private static void assertError(MockHttpServletResponse response, int status, String code) throws Exception {
        assertThat(response.getStatus()).as("status for " + code).isEqualTo(status);
        Matcher matcher = ERROR_CODE.matcher(response.getContentAsString());
        assertThat(matcher.find()).as("error body has a code the adapter can read").isTrue();
        assertThat(matcher.group(1)).isEqualTo(code);
    }

    private JsonNode ok(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return json.readTree(response.getContentAsString());
    }

    private MockHttpServletResponse move(String movement, String customerId, String key, String amount, String currency,
                                         String reference) throws Exception {
        return mvc.perform(asLoanService(post("/api/v1/customers/{id}/credit/" + movement, customerId))
                .header("x-idempotency-key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":%s,\"currency\":\"%s\",\"reference\":\"%s\"}".formatted(amount, currency, reference)))
            .andReturn().getResponse();
    }

    private String create(String email) throws Exception {
        String body = mvc.perform(post("/api/v1/customers")
                .header("x-fapi-interaction-id", "contract-banker")
                .with(jwt().jwt(j -> j.subject("banker-1")).authorities(new SimpleGrantedAuthority("ROLE_BANKER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"firstName": "Noor", "lastName": "Rahman", "email": "%s", "phoneNumber": "+971500000123",
                     "initialCreditLimit": 10000.00, "currency": "AED"}
                    """.formatted(email)))
            .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("customerId").asText();
    }

    /** What the loan adapter sends: its client-credentials token and an interaction id. */
    private static MockHttpServletRequestBuilder asLoanService(MockHttpServletRequestBuilder request) {
        return request.header("x-fapi-interaction-id", "loan-interaction-1")
            .with(jwt().jwt(j -> j.subject("service-account-svc-ln-loan-lifecycle").claim("azp", "svc-ln-loan-lifecycle"))
                .authorities(new SimpleGrantedAuthority("ROLE_SERVICE")));
    }
}
