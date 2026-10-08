package com.bank.customer;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Credit moves racing on one customer, over HTTP and against PostgreSQL, the
 * way several loans for one customer arrive at once. The invariant is
 * used_credit <= credit_limit after every interleaving, every accepted move
 * is journalled once, and a caller gets a business answer (200 or 422), not
 * a 409 "retry" for a race the service can settle itself.
 */
@SpringBootTest(properties = "customer.outbox.relay.enabled=false")
@AutoConfigureMockMvc
class CreditConcurrencyIT {

    /**
     * Writers racing on one customer. A request loses an optimistic race only
     * when another writer committed in between, so with 8 writers no request
     * loses more than 7 times, below the service's default of 10 attempts.
     */
    private static final int THREADS = 8;

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
    void concurrentReservesTakeExactlyTheLimitAndNeverOverdraw() throws Exception {
        String customerId = create("race@example.com", "10000.00");

        List<Callable<MockHttpServletResponse>> calls = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            String key = "LOAN-R" + i + ":reserve";
            calls.add(() -> reserve(customerId, "1500.00", key));
        }
        List<MockHttpServletResponse> responses = runTogether(calls);

        // 8 x 1500 asked against 10000: six fit (9000), two are refused.
        Map<Integer, Long> statuses = countStatuses(responses);
        assertThat(statuses).as("status -> count").containsOnlyKeys(200, 422);
        assertThat(statuses.get(200)).isEqualTo(6L);
        assertThat(statuses.get(422)).isEqualTo(2L);
        assertCreditInvariant(customerId, "10000.00", "9000.00");
        assertThat(jdbc.queryForObject("select count(*) from sc_cus_profile_kyc.credit_movement where customer_id = ?",
            Integer.class, customerId)).isEqualTo(6);
        assertThat(jdbc.queryForObject("select count(*) from sc_cus_profile_kyc.outbox_event where aggregate_id = ? "
            + "and event_type = 'Customer.Customer.CreditReserved.v1'", Integer.class, customerId)).isEqualTo(6);
    }

    @Test
    void aLimitLoweredWhileReservesRunNeverLeavesUsedCreditAboveTheLimit() throws Exception {
        String customerId = create("lowered@example.com", "10000.00");

        List<Callable<MockHttpServletResponse>> calls = new ArrayList<>();
        for (int i = 0; i < THREADS - 1; i++) {
            String key = "LOAN-L" + i + ":reserve";
            calls.add(() -> reserve(customerId, "1000.00", key));
        }
        calls.add(() -> mvc.perform(asBanker(put("/api/v1/customers/{id}/credit-limit", customerId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": 5000.00, \"currency\": \"AED\"}"))
            .andReturn().getResponse());
        List<MockHttpServletResponse> responses = runTogether(calls);

        MockHttpServletResponse lowering = responses.get(responses.size() - 1);
        List<MockHttpServletResponse> reserves = responses.subList(0, responses.size() - 1);
        assertThat(countStatuses(reserves).keySet()).as("reserve statuses").isSubsetOf(200, 422);
        assertThat(lowering.getStatus()).as("limit change").isIn(200, 422);
        if (lowering.getStatus() == 422) {
            assertThat(json.readTree(lowering.getContentAsString()).get("code").asText())
                .isEqualTo("CREDIT_LIMIT_BELOW_USED_CREDIT");
        }
        long accepted = reserves.stream().filter(r -> r.getStatus() == 200).count();
        Map<String, Object> row = jdbc.queryForMap(
            "select credit_limit, used_credit from sc_cus_profile_kyc.customer where customer_id = ?", customerId);
        BigDecimal limit = (BigDecimal) row.get("credit_limit");
        BigDecimal used = (BigDecimal) row.get("used_credit");
        assertThat(used).isEqualByComparingTo(new BigDecimal(accepted * 1000));
        assertThat(used).as("used credit never above the limit").isLessThanOrEqualTo(limit);
        assertThat(limit).isIn(new BigDecimal("5000.0000"), new BigDecimal("10000.0000"));
    }

    private void assertCreditInvariant(String customerId, String expectedLimit, String expectedUsed) {
        Map<String, Object> row = jdbc.queryForMap(
            "select credit_limit, used_credit from sc_cus_profile_kyc.customer where customer_id = ?", customerId);
        assertThat((BigDecimal) row.get("credit_limit")).isEqualByComparingTo(expectedLimit);
        assertThat((BigDecimal) row.get("used_credit")).isEqualByComparingTo(expectedUsed);
    }

    private static Map<Integer, Long> countStatuses(List<MockHttpServletResponse> responses) {
        return responses.stream().collect(Collectors.groupingBy(MockHttpServletResponse::getStatus, Collectors.counting()));
    }

    private static List<MockHttpServletResponse> runTogether(List<Callable<MockHttpServletResponse>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<MockHttpServletResponse>> futures = calls.stream()
                .map(call -> pool.submit(() -> {
                    start.await();
                    return call.call();
                }))
                .toList();
            start.countDown();
            List<MockHttpServletResponse> responses = new ArrayList<>();
            for (Future<MockHttpServletResponse> future : futures) {
                responses.add(future.get(60, TimeUnit.SECONDS));
            }
            return responses;
        } finally {
            pool.shutdownNow();
        }
    }

    private MockHttpServletResponse reserve(String customerId, String amount, String key) throws Exception {
        return mvc.perform(asService(post("/api/v1/customers/{id}/credit/reserve", customerId))
                .header("x-idempotency-key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": %s, \"currency\": \"AED\", \"reference\": \"%s\"}".formatted(amount, key.split(":")[0])))
            .andReturn().getResponse();
    }

    private String create(String email, String limit) throws Exception {
        String body = mvc.perform(asBanker(post("/api/v1/customers"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"firstName": "Noor", "lastName": "Rahman", "email": "%s", "phoneNumber": "+971500000123",
                     "initialCreditLimit": %s, "currency": "AED"}
                    """.formatted(email, limit)))
            .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("customerId").asText();
    }

    private static MockHttpServletRequestBuilder asBanker(MockHttpServletRequestBuilder request) {
        return request.header("x-fapi-interaction-id", "race-banker")
            .with(jwt().jwt(j -> j.subject("banker-1")).authorities(new SimpleGrantedAuthority("ROLE_BANKER")));
    }

    private static MockHttpServletRequestBuilder asService(MockHttpServletRequestBuilder request) {
        return request.header("x-fapi-interaction-id", "race-service")
            .with(jwt().jwt(j -> j.subject("service-account-loan").claim("azp", "svc-ln-loan-lifecycle"))
                .authorities(new SimpleGrantedAuthority("ROLE_SERVICE")));
    }
}
