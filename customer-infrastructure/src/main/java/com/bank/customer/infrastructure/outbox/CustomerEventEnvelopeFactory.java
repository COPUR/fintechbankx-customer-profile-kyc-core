package com.bank.customer.infrastructure.outbox;

import com.bank.customer.domain.Customer;
import com.bank.customer.domain.CustomerContactUpdatedEvent;
import com.bank.customer.domain.CustomerCreatedEvent;
import com.bank.customer.domain.CustomerCreditLimitUpdatedEvent;
import com.bank.customer.domain.CustomerCreditReleasedEvent;
import com.bank.customer.domain.CustomerCreditReservedEvent;
import com.bank.customer.domain.CustomerCreditScoreUpdatedEvent;
import com.bank.customer.domain.CustomerKycStatusChangedEvent;
import com.bank.shared.kernel.domain.DomainEvent;
import com.bank.shared.kernel.domain.Money;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Turns Customer domain events into the public envelope of the AsyncAPI
 * contract svc-cus-profile-kyc.yaml (asyncapi catalog): every event goes to
 * the one customer aggregate topic evt.cus.customer.v1 (ADR-019 s1), keyed by
 * customer id and named by its eventType Customer.Customer.&lt;Event&gt;.v1,
 * which the relay also sends as the eventType record header; money as
 * decimal strings. Names, e-mail addresses and phone numbers never
 * leave the service on events; consumers read them through the customer API.
 */
public class CustomerEventEnvelopeFactory {

    public static final String PRODUCER = "svc-cus-profile-kyc";
    public static final String AGGREGATE_TYPE = "Customer";
    /** One topic per aggregate (ADR-019 s1): all customer events, in order per customer. */
    public static final String TOPIC = "evt.cus.customer.v1";

    private final ObjectMapper objectMapper;

    public CustomerEventEnvelopeFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public OutboxEventJpaEntity toOutboxRow(Customer customer, DomainEvent event, String correlationId) {
        PublicEvent mapped = map(event);
        UUID eventId = UUID.fromString(event.getEventId());
        String aggregateId = customer.getId().getValue();
        long aggregateVersion = customer.getVersion() == null ? 0L : customer.getVersion();

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", mapped.eventType());
        envelope.put("occurredAt", event.getOccurredOn().toString());
        envelope.put("aggregateId", aggregateId);
        envelope.put("aggregateVersion", aggregateVersion);
        envelope.put("correlationId", correlationId);
        envelope.put("causationId", null);
        envelope.put("producer", PRODUCER);
        envelope.put("data", mapped.data());

        return new OutboxEventJpaEntity(eventId, AGGREGATE_TYPE, aggregateId, aggregateVersion,
            mapped.eventType(), mapped.topic(), toJson(envelope), correlationId, event.getOccurredOn());
    }

    static PublicEvent map(DomainEvent event) {
        return switch (event) {
            case CustomerCreatedEvent e -> new PublicEvent("Created", data(
                "customerId", e.getCustomerId().getValue()));
            case CustomerContactUpdatedEvent e -> new PublicEvent("ContactUpdated", data(
                "customerId", e.getCustomerId().getValue()));
            case CustomerCreditLimitUpdatedEvent e -> new PublicEvent("CreditLimitUpdated", data(
                "customerId", e.getCustomerId().getValue(),
                "oldCreditLimit", money(e.getOldCreditLimit()),
                "newCreditLimit", money(e.getNewCreditLimit())));
            case CustomerCreditReservedEvent e -> new PublicEvent("CreditReserved", data(
                "customerId", e.getCustomerId().getValue(),
                "reservedAmount", money(e.getReservedAmount())));
            case CustomerCreditReleasedEvent e -> new PublicEvent("CreditReleased", data(
                "customerId", e.getCustomerId().getValue(),
                "releasedAmount", money(e.getReleasedAmount())));
            // Never the score value (restricted data): consumers read it through GET /credit under the azp allow-list.
            case CustomerCreditScoreUpdatedEvent e -> new PublicEvent("CreditScoreUpdated", data(
                "customerId", e.getCustomerId().getValue(),
                "updatedAt", e.getOccurredOn().toString()));
            // Never the staff subject: who decided stays in the customer row (kyc_updated_by).
            case CustomerKycStatusChangedEvent e -> new PublicEvent("KycStatusChanged", data(
                "customerId", e.getCustomerId().getValue(),
                "kycStatus", e.getKycStatus().status().name(),
                "previousKycStatus", e.getPreviousStatus().name(),
                "kycSource", e.getKycStatus().source().name(),
                "verifiedAt", e.getKycStatus().verifiedAt() == null ? null : e.getKycStatus().verifiedAt().toString()));
            default -> throw new IllegalArgumentException(
                "No public contract for customer event " + event.getClass().getName());
        };
    }

    private static Map<String, Object> money(Money money) {
        return data("amount", money.getAmount().toPlainString(), "currency", money.getCurrency().getCurrencyCode());
    }

    private static Map<String, Object> data(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private String toJson(Map<String, Object> envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise customer event envelope", e);
        }
    }

    record PublicEvent(String eventName, Map<String, Object> data) {
        String topic() {
            return TOPIC;
        }

        String eventType() {
            return "Customer.Customer." + eventName + ".v1";
        }
    }
}
