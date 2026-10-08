package com.bank.customer.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Row of sc_cus_profile_kyc.customer. Persistence shape only; the Customer
 * aggregate in customer-domain holds the rules. Credit limit, used credit and
 * monthly income share the row's currency.
 */
@Entity
@Table(name = "customer")
public class CustomerJpaEntity {

    @Id
    @Column(name = "customer_id", length = 64)
    private String customerId;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(name = "email", length = 254)
    private String email;

    @Column(name = "phone_number", length = 32)
    private String phoneNumber;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "credit_limit", nullable = false, precision = 19, scale = 4)
    private BigDecimal creditLimit;

    @Column(name = "used_credit", nullable = false, precision = 19, scale = 4)
    private BigDecimal usedCredit;

    @Column(name = "credit_score")
    private Integer creditScore;

    @Column(name = "monthly_income", precision = 19, scale = 4)
    private BigDecimal monthlyIncome;

    @Column(name = "legacy_customer_id", updatable = false)
    private Long legacyCustomerId;

    @Column(name = "identity_user_id", length = 64)
    private String identityUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected CustomerJpaEntity() {
    }

    CustomerJpaEntity(String customerId) {
        this.customerId = customerId;
    }

    public String getCustomerId() { return customerId; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public String getEmail() { return email; }
    public String getPhoneNumber() { return phoneNumber; }
    public String getCurrency() { return currency; }
    public BigDecimal getCreditLimit() { return creditLimit; }
    public BigDecimal getUsedCredit() { return usedCredit; }
    public Integer getCreditScore() { return creditScore; }
    public String getIdentityUserId() { return identityUserId; }
    void setIdentityUserId(String identityUserId) { this.identityUserId = identityUserId; }
    public BigDecimal getMonthlyIncome() { return monthlyIncome; }
    public Long getLegacyCustomerId() { return legacyCustomerId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }

    void setFirstName(String firstName) { this.firstName = firstName; }
    void setLastName(String lastName) { this.lastName = lastName; }
    void setEmail(String email) { this.email = email; }
    void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
    void setCurrency(String currency) { this.currency = currency; }
    void setCreditLimit(BigDecimal creditLimit) { this.creditLimit = creditLimit; }
    void setUsedCredit(BigDecimal usedCredit) { this.usedCredit = usedCredit; }
    void setCreditScore(Integer creditScore) { this.creditScore = creditScore; }
    void setMonthlyIncome(BigDecimal monthlyIncome) { this.monthlyIncome = monthlyIncome; }
    void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
