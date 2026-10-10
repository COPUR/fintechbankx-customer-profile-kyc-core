package com.bank.customer.domain;

import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.DomainEvent;
import com.bank.shared.kernel.domain.Money;

import java.time.Instant;
import java.util.Optional;

/**
 * Domain Event indicating that reserved credit has been released for a customer
 */
public class CustomerCreditReleasedEvent implements DomainEvent {
    
    private final String eventId;
    private final CustomerId customerId;
    private final Money releasedAmount;
    private final String reference;
    private final Instant occurredOn;
    
    public CustomerCreditReleasedEvent(CustomerId customerId, Money releasedAmount) {
        this(customerId, releasedAmount, null);
    }

    /**
     * @param reference the reservation (loan id) the movement belongs to;
     *                  null for an untracked movement (for example a migrated balance)
     */
    public CustomerCreditReleasedEvent(CustomerId customerId, Money releasedAmount, String reference) {
        this.eventId = java.util.UUID.randomUUID().toString();
        this.customerId = customerId;
        this.releasedAmount = releasedAmount;
        this.reference = reference;
        this.occurredOn = Instant.now();
    }
    
    @Override
    public String getEventId() {
        return eventId;
    }
    
    @Override
    public Instant getOccurredOn() {
        return occurredOn;
    }
    
    public CustomerId getCustomerId() {
        return customerId;
    }
    
    public Money getReleasedAmount() {
        return releasedAmount;
    }

    /** The reservation reference (loan id) this movement belongs to; empty when untracked. */
    public Optional<String> getReference() {
        return Optional.ofNullable(reference);
    }
}