package com.bank.customer.domain;

import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.DomainEvent;
import com.bank.shared.kernel.domain.Money;

import java.time.Instant;
import java.util.Optional;

/**
 * Domain Event indicating that credit has been reserved for a customer
 */
public class CustomerCreditReservedEvent implements DomainEvent {
    
    private final String eventId;
    private final CustomerId customerId;
    private final Money reservedAmount;
    private final String reference;
    private final Instant occurredOn;
    
    public CustomerCreditReservedEvent(CustomerId customerId, Money reservedAmount) {
        this(customerId, reservedAmount, null);
    }

    /**
     * @param reference the reservation (loan id) the movement belongs to;
     *                  null for an untracked movement (for example a migrated balance)
     */
    public CustomerCreditReservedEvent(CustomerId customerId, Money reservedAmount, String reference) {
        this.eventId = java.util.UUID.randomUUID().toString();
        this.customerId = customerId;
        this.reservedAmount = reservedAmount;
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
    
    public Money getReservedAmount() {
        return reservedAmount;
    }

    /** The reservation reference (loan id) this movement belongs to; empty when untracked. */
    public Optional<String> getReference() {
        return Optional.ofNullable(reference);
    }
}