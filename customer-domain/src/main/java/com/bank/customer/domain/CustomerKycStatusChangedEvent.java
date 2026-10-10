package com.bank.customer.domain;

import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/** Staff changed a customer's KYC status (verified or rejected). */
public class CustomerKycStatusChangedEvent implements DomainEvent {

    private final String eventId = UUID.randomUUID().toString();
    private final CustomerId customerId;
    private final KycStatus.Status previousStatus;
    private final KycStatus kycStatus;
    private final Instant occurredOn;

    public CustomerKycStatusChangedEvent(CustomerId customerId, KycStatus.Status previousStatus, KycStatus kycStatus,
                                         Instant occurredOn) {
        this.customerId = customerId;
        this.previousStatus = previousStatus;
        this.kycStatus = kycStatus;
        this.occurredOn = occurredOn;
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

    public KycStatus.Status getPreviousStatus() {
        return previousStatus;
    }

    public KycStatus getKycStatus() {
        return kycStatus;
    }
}
