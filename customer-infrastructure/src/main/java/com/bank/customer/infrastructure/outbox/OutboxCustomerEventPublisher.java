package com.bank.customer.infrastructure.outbox;

import com.bank.customer.domain.Customer;
import com.bank.customer.domain.port.out.CustomerEventPublisher;
import com.bank.customer.infrastructure.web.CorrelationIdFilter;
import com.bank.shared.kernel.domain.DomainEvent;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Transactional outbox: writes each event's envelope in the caller's
 * transaction (MANDATORY), so the customer row and its events commit or roll
 * back together. {@link OutboxRelay} ships them to Kafka afterwards.
 */
@Component
public class OutboxCustomerEventPublisher implements CustomerEventPublisher {

    private final SpringDataOutboxRepository outbox;
    private final CustomerEventEnvelopeFactory envelopes;

    public OutboxCustomerEventPublisher(SpringDataOutboxRepository outbox, CustomerEventEnvelopeFactory envelopes) {
        this.outbox = outbox;
        this.envelopes = envelopes;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(Customer customer, List<DomainEvent> events) {
        String correlationId = currentCorrelationId();
        String traceparent = MDC.get(CorrelationIdFilter.TRACEPARENT_MDC_KEY);
        outbox.saveAll(events.stream()
            .map(event -> envelopes.toOutboxRow(customer, event, correlationId).withTraceparent(traceparent))
            .toList());
    }

    private static String currentCorrelationId() {
        String fromRequest = MDC.get(CorrelationIdFilter.MDC_KEY);
        return fromRequest != null ? fromRequest : UUID.randomUUID().toString();
    }
}
