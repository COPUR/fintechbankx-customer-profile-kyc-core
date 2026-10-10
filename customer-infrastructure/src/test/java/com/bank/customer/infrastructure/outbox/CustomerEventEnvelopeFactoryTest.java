package com.bank.customer.infrastructure.outbox;

import com.bank.customer.domain.Customer;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.DomainEvent;
import com.bank.shared.kernel.domain.Money;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerEventEnvelopeFactoryTest {

    private final ObjectMapper json = new ObjectMapper();
    private final CustomerEventEnvelopeFactory factory = new CustomerEventEnvelopeFactory(json);

    private static Customer customer(String id) {
        return Customer.createWithCreditScore(CustomerId.of(id), "Layla", "Private", "layla@example.com",
            "+971500000009", Money.aed(new BigDecimal("10000.00")), 700);
    }

    @Test
    void everyCustomerEventGoesToTheOneCustomerAggregateTopicNamedByItsType() {
        Customer customer = customer("CUST-ENV-1");
        customer.updateContactInformation("new@example.com", "+971500000010");
        customer.updateCreditLimit(Money.aed(new BigDecimal("45000.00")));
        customer.reserveCredit(Money.aed(new BigDecimal("1000.00")));
        customer.releaseUntrackedCredit(Money.aed(new BigDecimal("400.00")), Money.aed(BigDecimal.ZERO));
        customer.updateCreditScore(760);
        List<DomainEvent> events = customer.getDomainEvents();

        // ADR-019 s1: one topic per aggregate; the eventType (envelope and record header) names the event.
        assertThat(events).extracting(e -> CustomerEventEnvelopeFactory.map(e).topic())
            .hasSize(6).containsOnly("evt.cus.customer.v1");
        assertThat(events).extracting(e -> CustomerEventEnvelopeFactory.map(e).eventType()).containsExactly(
            "Customer.Customer.Created.v1", "Customer.Customer.ContactUpdated.v1",
            "Customer.Customer.CreditLimitUpdated.v1", "Customer.Customer.CreditReserved.v1",
            "Customer.Customer.CreditReleased.v1", "Customer.Customer.CreditScoreUpdated.v1");
        assertThat(CustomerEventEnvelopeFactory.map(events.get(2)).data()).containsKeys("oldCreditLimit", "newCreditLimit");
        assertThat(CustomerEventEnvelopeFactory.map(events.get(4)).data()).containsKey("releasedAmount");
        assertThat(CustomerEventEnvelopeFactory.map(events.get(5)).data()).containsOnlyKeys("customerId", "updatedAt");
    }

    /**
     * The score value never leaves on the event (restricted data); consumers
     * read it through GET /credit under the azp allow-list. The event says
     * only that the score changed, and when. Limit events keep their amounts.
     */
    @Test
    void aCreditScoreUpdateCarriesNoScoreValue() throws Exception {
        Customer customer = customer("CUST-ENV-S");
        customer.updateCreditScore(760);
        DomainEvent updated = customer.getDomainEvents().get(1);

        var mapped = CustomerEventEnvelopeFactory.map(updated);
        String payload = factory.toOutboxRow(customer, updated, "corr-score").getPayload();

        assertThat(mapped.topic()).isEqualTo("evt.cus.customer.v1");
        assertThat(mapped.data()).containsExactly(
            java.util.Map.entry("customerId", "CUST-ENV-S"),
            java.util.Map.entry("updatedAt", ((com.bank.customer.domain.CustomerCreditScoreUpdatedEvent) updated)
                .getOccurredOn().toString()));
        assertThat(json.readTree(payload).get("data").fieldNames()).toIterable()
            .containsExactly("customerId", "updatedAt")
            .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("score"));
        assertThat(payload).doesNotContain("newCreditScore", "760");
        assertThat(java.nio.file.Files.readString(java.nio.file.Path.of("..", "api", "asyncapi", "svc-cus-profile-kyc.yaml")))
            .as("contract schema has no newCreditScore property").doesNotContain("newCreditScore:");
    }

    /** Staff KYC decisions: status, previous status, source and verifiedAt; never who decided or personal data. */
    @Test
    void aKycStatusChangeGoesToTheAggregateTopicWithoutTheStaffSubject() {
        Customer customer = customer("CUST-ENV-K");
        customer.clearDomainEvents();
        java.time.Instant at = java.time.Instant.parse("2026-10-08T10:00:00Z");
        customer.verifyKyc("banker-sub-1", at);

        var mapped = CustomerEventEnvelopeFactory.map(customer.getDomainEvents().get(0));

        assertThat(mapped.topic()).isEqualTo("evt.cus.customer.v1");
        assertThat(mapped.eventType()).isEqualTo("Customer.Customer.KycStatusChanged.v1");
        assertThat(mapped.data()).containsExactly(
            java.util.Map.entry("customerId", "CUST-ENV-K"),
            java.util.Map.entry("kycStatus", "VERIFIED"),
            java.util.Map.entry("previousKycStatus", "PENDING"),
            java.util.Map.entry("kycSource", "STAFF"),
            java.util.Map.entry("verifiedAt", "2026-10-08T10:00:00Z"));
        customer.rejectKyc("banker-sub-1", at.plusSeconds(1));
        assertThat(CustomerEventEnvelopeFactory.map(customer.getDomainEvents().get(1)).data())
            .containsEntry("kycStatus", "REJECTED").containsEntry("verifiedAt", null);
    }

    @Test
    void everyTopicAndEventTypeIsDeclaredInTheAsyncApiContract() throws Exception {
        java.nio.file.Path contract = java.util.stream.Stream.of("api/asyncapi/svc-cus-profile-kyc.yaml",
                "../api/asyncapi/svc-cus-profile-kyc.yaml")
            .map(java.nio.file.Path::of).filter(java.nio.file.Files::exists).findFirst().orElseThrow();
        String spec = java.nio.file.Files.readString(contract);
        Customer customer = customer("CUST-ENV-C");
        customer.updateContactInformation("c@example.com", "+971500000011");
        customer.updateCreditLimit(Money.aed(new BigDecimal("20000.00")));
        customer.reserveCredit(Money.aed(new BigDecimal("10.00")));
        customer.releaseUntrackedCredit(Money.aed(new BigDecimal("10.00")), Money.aed(BigDecimal.ZERO));
        customer.updateCreditScore(710);
        customer.verifyKyc("banker-sub-1", java.time.Instant.parse("2026-10-08T10:00:00Z"));

        for (DomainEvent event : customer.getDomainEvents()) {
            var mapped = CustomerEventEnvelopeFactory.map(event);
            assertThat(spec).as("contract declares %s", mapped.topic()).contains("address: " + mapped.topic());
            assertThat(spec).as("contract declares %s", mapped.eventType()).contains(mapped.eventType());
        }
    }

    @Test
    void personalDataNeverLeavesOnEvents() throws Exception {
        Customer customer = customer("CUST-ENV-2");
        customer.updateContactInformation("new@example.com", "+971500000010");

        for (DomainEvent event : customer.getDomainEvents()) {
            String payload = factory.toOutboxRow(customer, event, "corr-pii").getPayload();
            assertThat(payload).doesNotContain("Layla", "Private", "example.com", "+9715");
            assertThat(json.readTree(payload).get("data").fieldNames()).toIterable().containsExactly("customerId");
        }
    }

    @Test
    void outboxRowCarriesTheStandardEnvelopeWithMoneyAsDecimalStrings() throws Exception {
        Customer customer = customer("CUST-ENV-3");
        customer.setVersion(4L);
        customer.reserveCredit(Money.aed(new BigDecimal("1250.5")));
        DomainEvent reserved = customer.getDomainEvents().get(1);

        OutboxEventJpaEntity row = factory.toOutboxRow(customer, reserved, "corr-3");
        JsonNode envelope = json.readTree(row.getPayload());

        assertThat(row.getEventId().toString()).isEqualTo(reserved.getEventId());
        assertThat(row.getAggregateType()).isEqualTo("Customer");
        assertThat(row.getAggregateId()).isEqualTo("CUST-ENV-3");
        assertThat(row.getAggregateVersion()).isEqualTo(4L);
        assertThat(row.getTopic()).isEqualTo("evt.cus.customer.v1");
        assertThat(row.getCorrelationId()).isEqualTo("corr-3");
        assertThat(envelope.get("producer").asText()).isEqualTo("svc-cus-profile-kyc");
        assertThat(envelope.get("eventType").asText()).isEqualTo("Customer.Customer.CreditReserved.v1");
        assertThat(envelope.get("aggregateVersion").asLong()).isEqualTo(4L);
        assertThat(envelope.get("causationId").isNull()).isTrue();
        assertThat(envelope.at("/data/reservedAmount/amount").asText()).isEqualTo("1250.50");
        assertThat(envelope.at("/data/reservedAmount/currency").asText()).isEqualTo("AED");
    }

    @Test
    void eventsWithoutAPublicContractAreRejected() {
        DomainEvent unknown = new DomainEvent() {
        };

        assertThatThrownBy(() -> CustomerEventEnvelopeFactory.map(unknown))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("No public contract");
    }
}
