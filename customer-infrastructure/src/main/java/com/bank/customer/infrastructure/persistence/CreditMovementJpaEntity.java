package com.bank.customer.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Row of sc_cus_profile_kyc.credit_movement: one applied reserve or release,
 * unique per (customer_id, idempotency_key).
 */
@Entity
@Table(name = "credit_movement")
public class CreditMovementJpaEntity {

    @Id
    @Column(name = "movement_id")
    private UUID movementId;

    @Column(name = "customer_id", nullable = false, length = 64, updatable = false)
    private String customerId;

    @Column(name = "idempotency_key", nullable = false, length = 128, updatable = false)
    private String idempotencyKey;

    @Column(name = "movement_type", nullable = false, length = 16, updatable = false)
    private String movementType;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Column(name = "reference", length = 128, updatable = false)
    private String reference;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected CreditMovementJpaEntity() {
    }

    CreditMovementJpaEntity(UUID movementId, String customerId, String idempotencyKey, String movementType,
                            String currency, BigDecimal amount, String reference, Instant occurredAt) {
        this.movementId = movementId;
        this.customerId = customerId;
        this.idempotencyKey = idempotencyKey;
        this.movementType = movementType;
        this.currency = currency;
        this.amount = amount;
        this.reference = reference;
        this.occurredAt = occurredAt;
    }

    public UUID getMovementId() { return movementId; }
    public String getCustomerId() { return customerId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getMovementType() { return movementType; }
    public String getCurrency() { return currency; }
    public BigDecimal getAmount() { return amount; }
    public String getReference() { return reference; }
    public Instant getOccurredAt() { return occurredAt; }
}
