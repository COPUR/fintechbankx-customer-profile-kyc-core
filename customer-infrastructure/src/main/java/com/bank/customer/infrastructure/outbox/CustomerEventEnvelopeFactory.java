package com.bank.customer.infrastructure.outbox;

import com.bank.customer.domain.Customer;
import com.bank.customer.domain.CustomerContactUpdatedEvent;
import com.bank.customer.domain.CustomerCreatedEvent;
import com.bank.customer.domain.CustomerCreditLimitUpdatedEvent;
import com.bank.customer.domain.CustomerCreditReleasedEvent;
import com.bank.customer.domain.CustomerCreditReservedEvent;
import com.bank.customer.domain.CustomerCreditScoreUpdatedEvent;
import com.bank.shared.kernel.domain.DomainEvent;
import com.bank.shared.kernel.domain.Money;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Turns Customer domain events into the public envelope of the AsyncAPI
 * contract svc-cus-profile-kyc.yaml (asyncapi catalog): topic
 * evt.cus.customer.&lt;event&gt;.v1, eventType Customer.Customer.&lt;Event&gt;.v1,
 * money as decimal strings. Names, e-mail addresses and phone numbers never
 * leave the service on events; consumers read them through the customer API.
 */
public class CustomerEventEnvelopeFactory {

    public static final String PRODUCER = "svc-cus-profile-kyc";
    public static final String AGGREGATE_TYPE = "Customer";

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
            case CustomerCreatedEvent e -> new PublicEvent("created", "Created", data(
                "customerId", e.getCustomerId().getValue()));
            case CustomerContactUpdatedEvent e -> new PublicEvent("contact-updated", "ContactUpdated", data(
                "customerId", e.getCustomerId().getValue()));
            case CustomerCreditLimitUpdatedEvent e -> new PublicEvent("credit-limit-updated", "CreditLimitUpdated", data(
                "customerId", e.getCustomerId().getValue(),
                "oldCreditLimit", money(e.getOldCreditLimit()),
                "newCreditLimit", money(e.getNewCreditLimit())));
            case CustomerCreditReservedEvent e -> new PublicEvent("credit-reserved", "CreditReserved", data(
                "customerId", e.getCustomerId().getValue(),
                "reservedAmount", money(e.getReservedAmount())));
            case CustomerCreditReleasedEvent e -> new PublicEvent("credit-released", "CreditReleased", data(
                "customerId", e.getCustomerId().getValue(),
                "releasedAmount", money(e.getReleasedAmount())));
            case CustomerCreditScoreUpdatedEvent e -> new PublicEvent("credit-score-updated", "CreditScoreUpdated", data(
                "customerId", e.getCustomerId().getValue(),
                "newCreditScore", e.getNewCreditScore()));
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

    record PublicEvent(String topicSuffix, String eventName, Map<String, Object> data) {
        String topic() {
            return "evt.cus.customer." + topicSuffix + ".v1";
        }

        String eventType() {
            return "Customer.Customer." + eventName + ".v1";
        }
    }
}
