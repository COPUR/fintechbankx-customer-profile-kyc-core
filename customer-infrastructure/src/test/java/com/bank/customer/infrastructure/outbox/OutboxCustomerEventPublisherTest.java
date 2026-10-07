package com.bank.customer.infrastructure.outbox;

import com.bank.customer.domain.Customer;
import com.bank.customer.infrastructure.web.CorrelationIdFilter;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OutboxCustomerEventPublisherTest {

    private final SpringDataOutboxRepository outbox = mock(SpringDataOutboxRepository.class);
    private final OutboxCustomerEventPublisher publisher =
        new OutboxCustomerEventPublisher(outbox, new CustomerEventEnvelopeFactory(new ObjectMapper()));

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void writesOneRowPerEventWithTheRequestCorrelationId() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "corr-req");
        Customer customer = Customer.create(CustomerId.of("CUST-PUB-1"), "Ali", "Sample", "ali@example.com", null,
            Money.aed(new BigDecimal("5000.00")));
        customer.reserveCredit(Money.aed(new BigDecimal("100.00")));

        publisher.publish(customer, customer.getDomainEvents());

        ArgumentCaptor<List<OutboxEventJpaEntity>> rows = ArgumentCaptor.forClass(List.class);
        verify(outbox).saveAll(rows.capture());
        assertThat(rows.getValue()).extracting(OutboxEventJpaEntity::getTopic)
            .containsExactly("evt.cus.customer.created.v1", "evt.cus.customer.credit-reserved.v1");
        assertThat(rows.getValue()).extracting(OutboxEventJpaEntity::getCorrelationId).containsOnly("corr-req");
    }

    @Test
    @SuppressWarnings("unchecked")
    void eventsRaisedOutsideARequestGetAFreshCorrelationId() {
        Customer customer = Customer.create(CustomerId.of("CUST-PUB-2"), "Ali", "Sample", "ali@example.com", null,
            Money.aed(new BigDecimal("5000.00")));

        publisher.publish(customer, customer.getDomainEvents());

        ArgumentCaptor<List<OutboxEventJpaEntity>> rows = ArgumentCaptor.forClass(List.class);
        verify(outbox).saveAll(rows.capture());
        assertThat(rows.getValue().getFirst().getCorrelationId()).matches("[0-9a-f-]{36}");
    }
}
