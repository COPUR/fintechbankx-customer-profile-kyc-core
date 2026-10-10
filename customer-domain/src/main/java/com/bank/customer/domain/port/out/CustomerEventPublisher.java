package com.bank.customer.domain.port.out;

import com.bank.customer.domain.Customer;
import com.bank.shared.kernel.domain.DomainEvent;

import java.util.List;

/**
 * Publishes a customer's domain events to other bounded contexts. Adapters
 * must take part in the caller's transaction so that state and events commit
 * together.
 */
public interface CustomerEventPublisher {

    void publish(Customer customer, List<DomainEvent> events);
}
