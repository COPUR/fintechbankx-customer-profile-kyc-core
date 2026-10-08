package com.bank.customer;

import com.bank.customer.domain.Customer;
import com.bank.customer.domain.port.in.CreditMovementCommand;
import com.bank.customer.domain.port.in.MoveCreditUseCase;
import com.bank.customer.domain.port.out.CustomerRepository;
import com.bank.customer.infrastructure.outbox.OutboxRelay;
import com.bank.customer.infrastructure.outbox.SpringDataOutboxRepository;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Boots the whole service against PostgreSQL: Flyway builds sc_cus_profile_kyc,
 * Hibernate validates the entities against it, and customers are created and
 * have credit reserved over HTTP the way the loan service calls it, with
 * their events landing in the outbox and then on (a mocked) Kafka.
 */
@SpringBootTest(properties = "customer.outbox.relay.enabled=false")
@AutoConfigureMockMvc
class CustomerServiceIT {

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
    @Autowired CustomerRepository customers;
    @Autowired MoveCreditUseCase moveCredit;
    @Autowired SpringDataOutboxRepository outbox;
    @Autowired PlatformTransactionManager transactionManager;
    @MockBean KafkaTemplate<String, String> kafka;

    @BeforeEach
    void cleanTables() {
        jdbc.update("delete from sc_cus_profile_kyc.outbox_event");
        jdbc.update("delete from sc_cus_profile_kyc.credit_movement");
        jdbc.update("delete from sc_cus_profile_kyc.customer");
    }

    @Test
    void flywayCreatesOnlyTheTablesThisServiceOwns() {
        List<String> tables = jdbc.queryForList("""
            select table_name from information_schema.tables
            where table_schema = 'sc_cus_profile_kyc' and table_name <> 'flyway_schema_history'
            order by table_name
            """, String.class);

        assertThat(tables).containsExactly("credit_movement", "customer", "outbox_event");
    }

    @Test
    void createdCustomerIsStoredAndItsEventCarriesNoPersonalData() throws Exception {
        String customerId = create("noor@example.com", "20000.00");

        assertThat(jdbc.queryForMap("select first_name, email, currency, credit_limit, version from sc_cus_profile_kyc.customer where customer_id = ?", customerId))
            .containsEntry("first_name", "Noor")
            .containsEntry("email", "noor@example.com")
            .containsEntry("currency", "AED")
            .containsEntry("version", 0L)
            .hasEntrySatisfying("credit_limit", v -> assertThat((BigDecimal) v).isEqualByComparingTo("20000.00"));

        String payload = jdbc.queryForObject(
            "select payload::text from sc_cus_profile_kyc.outbox_event where aggregate_id = ?", String.class, customerId);
        JsonNode created = json.readTree(payload);
        assertThat(created.get("eventType").asText()).isEqualTo("Customer.Customer.Created.v1");
        assertThat(created.get("producer").asText()).isEqualTo("svc-cus-profile-kyc");
        assertThat(created.get("correlationId").asText()).isEqualTo("it-interaction-1");
        assertThat(payload).doesNotContain("Noor", "noor@example.com");
    }

    @Test
    void duplicateEmailIsRejected() throws Exception {
        create("dup@example.com", "5000.00");

        String body = mvc.perform(asBanker(post("/api/v1/customers"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(customerJson("DUP@example.com", "5000.00")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CUSTOMER_ALREADY_EXISTS"))
            .andReturn().getResponse().getContentAsString();
        assertThat(body.toLowerCase()).doesNotContain("dup@example.com", "noor", "rahman");
        assertThat(jdbc.queryForObject("select count(*) from sc_cus_profile_kyc.customer", Integer.class)).isEqualTo(1);
    }

    @Test
    void retriedReservationFromTheLoanServiceMovesCreditOnce() throws Exception {
        String customerId = create("retry@example.com", "10000.00");

        // The loan service derives the key from the loan id, so its retry resends the same key.
        reserve(customerId, "2500.00", "LOAN-1:reserve").andExpect(status().isOk())
            .andExpect(jsonPath("$.availableCredit").value(7500.00));
        reserve(customerId, "2500.00", "LOAN-1:reserve").andExpect(status().isOk())
            .andExpect(jsonPath("$.availableCredit").value(7500.00));

        assertThat(jdbc.queryForObject("select used_credit from sc_cus_profile_kyc.customer where customer_id = ?", BigDecimal.class, customerId))
            .isEqualByComparingTo("2500.00");
        assertThat(jdbc.queryForObject("select count(*) from sc_cus_profile_kyc.credit_movement", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select reference from sc_cus_profile_kyc.credit_movement", String.class)).isEqualTo("LOAN-1");
        assertThat(jdbc.queryForList("select event_type from sc_cus_profile_kyc.outbox_event where aggregate_id = ? order by created_seq", String.class, customerId))
            .containsExactly("Customer.Customer.Created.v1", "Customer.Customer.CreditReserved.v1");

        reserve(customerId, "2600.00", "LOAN-1:reserve")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        mvc.perform(asService(post("/api/v1/customers/{id}/credit/release", customerId))
                .header("x-idempotency-key", "LOAN-1:release")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": 2500.00, \"currency\": \"AED\", \"reference\": \"LOAN-1\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.usedCredit").value(0));
    }

    @Test
    void reserveAndReleaseReturnOnlyTheCreditPosition() throws Exception {
        String customerId = create("position@example.com", "10000.00");

        for (var result : List.of(
                reserve(customerId, "2500.00", "LOAN-9:reserve"),
                mvc.perform(asService(post("/api/v1/customers/{id}/credit/release", customerId))
                    .header("x-idempotency-key", "LOAN-9:release")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"amount\": 1000.00, \"currency\": \"AED\", \"reference\": \"LOAN-9\"}")))) {
            JsonNode body = json.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(body.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("customerId", "currency", "creditLimit", "usedCredit", "availableCredit");
            assertThat(body.has("firstName") || body.has("lastName") || body.has("email") || body.has("phoneNumber")
                || body.has("monthlyIncome") || body.has("creditScore")).isFalse();
            assertThat(body.get("customerId").asText()).isEqualTo(customerId);
            assertThat(body.get("currency").asText()).isEqualTo("AED");
        }
        mvc.perform(asService(get("/api/v1/customers/{id}/credit", customerId)))
            .andExpect(jsonPath("$.usedCredit").value(1500.00))
            .andExpect(jsonPath("$.availableCredit").value(8500.00));
    }

    @Test
    void invalidAmountsAndCurrenciesAreA400AndMoveNothing() throws Exception {
        String customerId = create("invalid@example.com", "10000.00");
        List<String> invalidBodies = List.of(
            "{\"currency\": \"AED\", \"reference\": \"LOAN-5\"}",
            "{\"amount\": 10.00, \"reference\": \"LOAN-5\"}",
            "{\"amount\": 0, \"currency\": \"AED\", \"reference\": \"LOAN-5\"}",
            "{\"amount\": -10.00, \"currency\": \"AED\", \"reference\": \"LOAN-5\"}",
            "{\"amount\": 10.00, \"currency\": \"ZZZ\", \"reference\": \"LOAN-5\"}",
            "{\"amount\": 10.00, \"currency\": \"dirham\", \"reference\": \"LOAN-5\"}");

        int key = 0;
        for (String body : invalidBodies) {
            for (String movement : List.of("reserve", "release")) {
                mvc.perform(asService(post("/api/v1/customers/{id}/credit/" + movement, customerId))
                        .header("x-idempotency-key", "invalid-" + key++)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.message").isNotEmpty())
                    .andExpect(jsonPath("$.interactionId").value("it-interaction-3"));
            }
            mvc.perform(asBanker(put("/api/v1/customers/{id}/credit-limit", customerId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").isNotEmpty());
        }
        assertThat(jdbc.queryForMap("select credit_limit, used_credit from sc_cus_profile_kyc.customer where customer_id = ?", customerId))
            .hasEntrySatisfying("credit_limit", v -> assertThat((BigDecimal) v).isEqualByComparingTo("10000.00"))
            .hasEntrySatisfying("used_credit", v -> assertThat((BigDecimal) v).isEqualByComparingTo("0"));
        assertThat(jdbc.queryForObject("select count(*) from sc_cus_profile_kyc.credit_movement", Integer.class)).isZero();
    }

    @Test
    void aCreditMovementWithoutAnIdempotencyKeyIsRefused() throws Exception {
        String customerId = create("nokey@example.com", "10000.00");

        mvc.perform(asService(post("/api/v1/customers/{id}/credit/reserve", customerId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": 10.00, \"currency\": \"AED\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value("x-idempotency-key header is required"));
        assertThat(jdbc.queryForObject("select used_credit from sc_cus_profile_kyc.customer where customer_id = ?", BigDecimal.class, customerId))
            .isEqualByComparingTo("0");
    }

    @Test
    void insufficientCreditIsA422ThatDoesNotDiscloseTheLimit() throws Exception {
        String customerId = create("small@example.com", "1000.00");

        reserve(customerId, "1000.01", "LOAN-3:reserve")
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("INSUFFICIENT_CREDIT"))
            .andExpect(jsonPath("$.message").value(not(containsString("1000"))));
        assertThat(jdbc.queryForObject("select count(*) from sc_cus_profile_kyc.credit_movement", Integer.class)).isZero();
    }

    @Test
    void servicesReadOnlyTheCreditPositionAndOnlyListedServicesMayUseIt() throws Exception {
        String customerId = create("self@example.com", "3000.00");

        mvc.perform(get("/api/v1/customers/{id}", customerId).with(jwt().jwt(j -> j.subject(customerId))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/customers/{id}", customerId).with(jwt().jwt(j -> j.subject("someone-else"))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
            .andExpect(status().isForbidden());
        mvc.perform(asService(get("/api/v1/customers/{id}", customerId)))
            .andExpect(status().isForbidden());
        mvc.perform(asService(get("/api/v1/customers/{id}/credit", customerId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.availableCredit").value(3000.00))
            .andExpect(jsonPath("$.currency").value("AED"))
            .andExpect(jsonPath("$.email").doesNotExist())
            .andExpect(jsonPath("$.firstName").doesNotExist());
        mvc.perform(asUnlistedService(get("/api/v1/customers/{id}/credit", customerId)))
            .andExpect(status().isForbidden());
        mvc.perform(asUnlistedService(post("/api/v1/customers/{id}/credit/reserve", customerId))
                .header("x-idempotency-key", "other-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": 10.00, \"currency\": \"AED\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/customers/{id}/credit/reserve", customerId)
                .with(jwt().jwt(j -> j.subject(customerId)).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                .header("x-idempotency-key", "self-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": 10.00, \"currency\": \"AED\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void migratedMonolithCustomerLoadsAndCanReserveCredit() throws Exception {
        jdbc.update("""
            insert into sc_cus_profile_kyc.customer
              (customer_id, first_name, last_name, currency, credit_limit, used_credit, legacy_customer_id, created_at, updated_at, version)
            values ('42', 'Legacy', 'Customer', 'AED', 50000, 20000, 42, timestamp '2024-01-01 10:00', timestamp '2024-06-01 10:00', 3)
            """);

        mvc.perform(asBanker(get("/api/v1/customers/{id}", "42")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").doesNotExist());
        mvc.perform(asService(get("/api/v1/customers/{id}/credit", "42")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.availableCredit").value(30000.00));
        reserve("42", "30000.00", "LOAN-42:reserve").andExpect(status().isOk());

        assertThat(jdbc.queryForMap("select used_credit, version from sc_cus_profile_kyc.customer where customer_id = '42'"))
            .containsEntry("version", 4L)
            .hasEntrySatisfying("used_credit", v -> assertThat((BigDecimal) v).isEqualByComparingTo("50000.00"));
    }

    @Test
    void staleAggregateCannotOverwriteANewerVersion() throws Exception {
        String customerId = create("stale@example.com", "8000.00");
        Customer stale = customers.findById(CustomerId.of(customerId)).orElseThrow();
        moveCredit.reserveCredit(new CreditMovementCommand(CustomerId.of(customerId), Money.aed(new BigDecimal("8000.00")), "stale-1", null));

        stale.reserveCredit(Money.aed(new BigDecimal("8000.00")));

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> customers.save(stale)))
            .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(jdbc.queryForObject("select used_credit from sc_cus_profile_kyc.customer where customer_id = ?", BigDecimal.class, customerId))
            .isEqualByComparingTo("8000.00");
    }

    @Test
    @SuppressWarnings("unchecked")
    void relayPublishesPendingEventsInOrderKeyedByCustomerId() throws Exception {
        String customerId = create("relay@example.com", "4000.00");
        moveCredit.reserveCredit(new CreditMovementCommand(CustomerId.of(customerId), Money.aed(new BigDecimal("100.00")), "relay-1", null));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
        OutboxRelay relay = new OutboxRelay(outbox, kafka, new TransactionTemplate(transactionManager),
            Clock.systemUTC(), 100, Duration.ofSeconds(5), Duration.ofDays(7));

        int published = relay.relayOnce();

        assertThat(published).isEqualTo(2);
        assertThat(outbox.countByPublishedAtIsNull()).isZero();
        ArgumentCaptor<ProducerRecord<String, String>> records = ArgumentCaptor.forClass(ProducerRecord.class);
        Mockito.verify(kafka, Mockito.times(2)).send(records.capture());
        assertThat(records.getAllValues()).extracting(ProducerRecord::topic)
            .containsExactly("evt.cus.customer.created.v1", "evt.cus.customer.credit-reserved.v1");
        assertThat(records.getAllValues()).extracting(ProducerRecord::key).containsOnly(customerId);
    }

    @Test
    void unknownCustomerIsA404WithTheInteractionId() throws Exception {
        mvc.perform(asBanker(get("/api/v1/customers/{id}", "CUST-MISSING")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"))
            .andExpect(jsonPath("$.interactionId").value("it-interaction-1"));
    }

    @Test
    void apiRejectsCallsWithoutAToken() throws Exception {
        mvc.perform(get("/api/v1/customers/{id}", "CUST-ANY")).andExpect(status().isUnauthorized());
    }

    private String create(String email, String limit) throws Exception {
        String body = mvc.perform(asBanker(post("/api/v1/customers"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(customerJson(email, limit)))
            .andExpect(status().isCreated())
            .andExpect(header().string("x-fapi-interaction-id", "it-interaction-1"))
            .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("customerId").asText();
    }

    private org.springframework.test.web.servlet.ResultActions reserve(String customerId, String amount, String key) throws Exception {
        return mvc.perform(asService(post("/api/v1/customers/{id}/credit/reserve", customerId))
            .header("x-idempotency-key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"amount\": %s, \"currency\": \"AED\", \"reference\": \"%s\"}".formatted(amount, key.split(":")[0])));
    }

    private static String customerJson(String email, String limit) {
        return """
            {"firstName": "Noor", "lastName": "Rahman", "email": "%s", "phoneNumber": "+971500000123",
             "initialCreditLimit": %s, "currency": "AED"}
            """.formatted(email, limit);
    }

    private static MockHttpServletRequestBuilder asBanker(MockHttpServletRequestBuilder request) {
        return request.header("x-fapi-interaction-id", "it-interaction-1")
            .with(jwt().jwt(j -> j.subject("banker-1")).authorities(new SimpleGrantedAuthority("ROLE_BANKER")));
    }

    private static MockHttpServletRequestBuilder asService(MockHttpServletRequestBuilder request) {
        return request.header("x-fapi-interaction-id", "it-interaction-3")
            .with(jwt().jwt(j -> j.subject("service-account-loan").claim("azp", "svc-ln-loan-lifecycle"))
                .authorities(new SimpleGrantedAuthority("ROLE_SERVICE")));
    }

    /** A client-credentials client that holds SERVICE but is not on SERVICE_CALLERS. */
    private static MockHttpServletRequestBuilder asUnlistedService(MockHttpServletRequestBuilder request) {
        return request.header("x-fapi-interaction-id", "it-interaction-4")
            .with(jwt().jwt(j -> j.subject("service-account-risk").claim("azp", "svc-rsk-decisioning"))
                .authorities(new SimpleGrantedAuthority("ROLE_SERVICE")));
    }
}
