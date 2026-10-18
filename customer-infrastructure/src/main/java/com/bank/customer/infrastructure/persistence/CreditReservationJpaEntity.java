package com.bank.customer.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Row of sc_cus_profile_kyc.credit_reservation: the credit reserved and
 * released under one reference for one customer, unique per
 * (customer_id, reference). No version column of its own: every change is
 * written in the transaction that also changes the customer row, whose
 * optimistic version decides races.
 */
@Entity
@Table(name = "credit_reservation")
public class CreditReservationJpaEntity {

    @Id
    @Column(name = "reservation_id")
    private UUID reservationId;

    @Column(name = "customer_id", nullable = false, length = 64, updatable = false)
    private String customerId;

    @Column(name = "reference", nullable = false, length = 128, updatable = false)
    private String reference;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "reserved_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal reservedAmount;

    @Column(name = "released_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal releasedAmount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CreditReservationJpaEntity() {
    }

    CreditReservationJpaEntity(UUID reservationId, String customerId, String reference, String currency, Instant createdAt) {
        this.reservationId = reservationId;
        this.customerId = customerId;
        this.reference = reference;
        this.currency = currency;
        this.createdAt = createdAt;
    }

    void setAmounts(BigDecimal reservedAmount, BigDecimal releasedAmount, Instant at) {
        this.reservedAmount = reservedAmount;
        this.releasedAmount = releasedAmount;
        this.updatedAt = at;
    }

    public UUID getReservationId() { return reservationId; }
    public String getCustomerId() { return customerId; }
    public String getReference() { return reference; }
    public String getCurrency() { return currency; }
    public BigDecimal getReservedAmount() { return reservedAmount; }
    public BigDecimal getReleasedAmount() { return releasedAmount; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
