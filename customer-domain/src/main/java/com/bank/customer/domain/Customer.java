package com.bank.customer.domain;

import com.bank.shared.kernel.domain.AggregateRoot;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Customer Aggregate Root
 * 
 * Represents a banking customer with their personal information,
 * credit profile, and business rules for customer management.
 */
public class Customer extends AggregateRoot<CustomerId> {
    
    private static final int MIN_CREDIT_SCORE = 300;
    private static final int MAX_CREDIT_SCORE = 850;
    private static final Money MIN_MONTHLY_INCOME = Money.aed(new BigDecimal("1000.00"));
    private static final int MIN_CREDIT_SCORE_FOR_LOAN = 600;
    
    private CustomerId customerId;
    private String firstName;
    private String lastName;
    private String email;
    private String phoneNumber;
    private CreditProfile creditProfile;
    private Integer creditScore;
    private Money monthlyIncome;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private IdentityUserId identityUserId;
    private KycStatus kycStatus = KycStatus.pending();

    /** Rule of the Keycloak user attribute customer_id (platform contract). */
    private static final java.util.regex.Pattern IDENTITY_ATTRIBUTE_VALUE =
        java.util.regex.Pattern.compile("^[A-Za-z0-9-]{1,64}$");
    
    // Private constructor for JPA
    protected Customer() {}
    
    private Customer(CustomerId customerId, String firstName, String lastName, 
                    String email, String phoneNumber, CreditProfile creditProfile) {
        this.customerId = Objects.requireNonNull(customerId, "Customer ID cannot be null");
        this.firstName = Objects.requireNonNull(firstName, "First name cannot be null");
        this.lastName = Objects.requireNonNull(lastName, "Last name cannot be null");
        this.email = Objects.requireNonNull(email, "Email cannot be null");
        this.phoneNumber = phoneNumber;
        this.creditProfile = Objects.requireNonNull(creditProfile, "Credit profile cannot be null");
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        
        // Domain event
        addDomainEvent(new CustomerCreatedEvent(customerId, firstName + " " + lastName));
    }
    
    private Customer(CustomerId customerId, String firstName, String lastName, 
                    String email, String phoneNumber, Money monthlyIncome, Integer creditScore) {
        this.customerId = Objects.requireNonNull(customerId, "Customer ID cannot be null");
        this.firstName = Objects.requireNonNull(firstName, "First name cannot be null");
        this.lastName = Objects.requireNonNull(lastName, "Last name cannot be null");
        this.email = Objects.requireNonNull(email, "Email cannot be null");
        this.phoneNumber = phoneNumber;
        this.monthlyIncome = validateMonthlyIncome(monthlyIncome);
        this.creditScore = validateCreditScore(creditScore);
        this.creditProfile = CreditProfile.create(calculateCreditLimit());
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        
        // Domain event
        addDomainEvent(new CustomerCreatedEvent(customerId, firstName + " " + lastName));
    }
    
    public static Customer create(CustomerId customerId, String firstName, String lastName,
                                String email, String phoneNumber, Money creditLimit) {
        validateCustomerData(firstName, lastName, email);
        CreditProfile creditProfile = CreditProfile.create(creditLimit);
        return new Customer(customerId, firstName, lastName, email, phoneNumber, creditProfile);
    }
    
    /**
     * Rebuilds a customer from persisted state. Raises no events and skips the
     * creation rules, which legacy rows (no email, no income) do not meet.
     */
    public static Customer rehydrate(CustomerSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "Snapshot cannot be null");
        Customer customer = new Customer();
        customer.customerId = Objects.requireNonNull(snapshot.customerId(), "Customer ID cannot be null");
        customer.firstName = snapshot.firstName();
        customer.lastName = snapshot.lastName();
        customer.email = snapshot.email();
        customer.phoneNumber = snapshot.phoneNumber();
        customer.creditProfile = CreditProfile.create(snapshot.creditLimit(), snapshot.usedCredit());
        customer.creditScore = snapshot.creditScore();
        customer.monthlyIncome = snapshot.monthlyIncome();
        customer.createdAt = snapshot.createdAt();
        customer.updatedAt = snapshot.updatedAt();
        customer.identityUserId = snapshot.identityUserId();
        customer.kycStatus = snapshot.kycStatus() == null ? KycStatus.pending() : snapshot.kycStatus();
        customer.setVersion(snapshot.version());
        return customer;
    }

    public static Customer createWithCreditScore(CustomerId customerId, String firstName, String lastName,
                                               String email, String phoneNumber, Money monthlyIncome, Integer creditScore) {
        validateCustomerData(firstName, lastName, email);
        return new Customer(customerId, firstName, lastName, email, phoneNumber, monthlyIncome, creditScore);
    }
    
    private static void validateCustomerData(String firstName, String lastName, String email) {
        if (firstName == null || firstName.trim().isEmpty()) {
            throw new IllegalArgumentException("First name cannot be null or empty");
        }
        if (lastName == null || lastName.trim().isEmpty()) {
            throw new IllegalArgumentException("Last name cannot be null or empty");
        }
        if (email == null || !isValidEmail(email)) {
            throw new IllegalArgumentException("Email must be valid");
        }
    }
    
    private static boolean isValidEmail(String email) {
        if (email == null || email.trim().isEmpty()) {
            return false;
        }
        // Basic email validation: contains @ and has text before and after @
        int atIndex = email.indexOf('@');
        return atIndex > 0 && atIndex < email.length() - 1 && email.indexOf('@', atIndex + 1) == -1;
    }
    
    private Money validateMonthlyIncome(Money monthlyIncome) {
        if (monthlyIncome == null) {
            throw new IllegalArgumentException("Monthly income cannot be null");
        }
        if (monthlyIncome.compareTo(MIN_MONTHLY_INCOME) < 0) {
            throw new IllegalArgumentException(
                String.format("Monthly income must be at least %s", MIN_MONTHLY_INCOME));
        }
        return monthlyIncome;
    }
    
    private Integer validateCreditScore(Integer creditScore) {
        if (creditScore == null) {
            throw new IllegalArgumentException("Credit score cannot be null");
        }
        if (creditScore < MIN_CREDIT_SCORE || creditScore > MAX_CREDIT_SCORE) {
            throw new IllegalArgumentException(
                String.format("Credit score must be between %d and %d", MIN_CREDIT_SCORE, MAX_CREDIT_SCORE));
        }
        return creditScore;
    }
    
    private Money calculateCreditLimit() {
        if (monthlyIncome == null && creditProfile != null) {
            // No income on file (migrated or created with a fixed limit): keep the assigned limit.
            return creditProfile.getCreditLimit();
        }
        if (monthlyIncome == null || creditScore == null) {
            return Money.zero(MIN_MONTHLY_INCOME.getCurrency());
        }
        
        // Business rule: Credit limit based on credit score and monthly income
        BigDecimal baseMultiplier = new BigDecimal("3");
        if (creditScore >= 750) {
            baseMultiplier = new BigDecimal("5");
        } else if (creditScore >= 650) {
            baseMultiplier = new BigDecimal("4");
        }
        
        return monthlyIncome.multiply(baseMultiplier);
    }
    
    @Override
    public CustomerId getId() {
        return customerId;
    }
    
    public String getFirstName() {
        return firstName;
    }
    
    public String getLastName() {
        return lastName;
    }
    
    public String getFullName() {
        return firstName + " " + lastName;
    }
    
    public String getEmail() {
        return email;
    }
    
    public String getPhoneNumber() {
        return phoneNumber;
    }
    
    public CreditProfile getCreditProfile() {
        return creditProfile;
    }
    
    public Integer getCreditScore() {
        return creditScore;
    }
    
    public Money getMonthlyIncome() {
        return monthlyIncome;
    }
    
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
    
    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
    
    public IdentityUserId getIdentityUserId() {
        return identityUserId;
    }

    /**
     * Links this customer to the end user's identity account. One identity user
     * per customer: linking the same user again changes nothing and returns
     * false; a different user is a conflict. Raises no event (the link is not
     * part of the published contract).
     *
     * @return true if the link was added
     */
    public KycStatus getKycStatus() {
        return kycStatus;
    }

    /**
     * Staff verified the customer's KYC. Same status again changes nothing.
     * @return whether the status changed
     */
    public boolean verifyKyc(String by, java.time.Instant at) {
        return changeKyc(KycStatus.Status.VERIFIED, by, at);
    }

    /**
     * Staff rejected the customer's KYC (also revokes a verification).
     * @return whether the status changed
     */
    public boolean rejectKyc(String by, java.time.Instant at) {
        return changeKyc(KycStatus.Status.REJECTED, by, at);
    }

    private boolean changeKyc(KycStatus.Status target, String by, java.time.Instant at) {
        if (by == null || by.isBlank()) {
            throw new IllegalArgumentException("Who changed the KYC status is required");
        }
        Objects.requireNonNull(at, "When the KYC status changed is required");
        if (kycStatus.status() == target) {
            return false;
        }
        KycStatus.Status previous = kycStatus.status();
        this.kycStatus = new KycStatus(target, KycStatus.Source.STAFF,
            target == KycStatus.Status.VERIFIED ? at : null, by);
        this.updatedAt = LocalDateTime.now();
        addDomainEvent(new CustomerKycStatusChangedEvent(customerId, previous, kycStatus, at));
        return true;
    }

    public boolean linkIdentity(IdentityUserId userId) {
        Objects.requireNonNull(userId, "Identity user id cannot be null");
        if (userId.equals(identityUserId)) {
            return false;
        }
        if (identityUserId != null) {
            throw new IdentityLinkConflictException();
        }
        if (!IDENTITY_ATTRIBUTE_VALUE.matcher(customerId.getValue()).matches()) {
            throw new IllegalStateException("Customer id cannot be used as the customer_id identity attribute");
        }
        this.identityUserId = userId;
        this.updatedAt = LocalDateTime.now();
        return true;
    }

    public void updateContactInformation(String email, String phoneNumber) {
        if (email != null && isValidEmail(email)) {
            this.email = email;
        }
        if (phoneNumber != null && !phoneNumber.trim().isEmpty()) {
            this.phoneNumber = phoneNumber;
        }
        this.updatedAt = LocalDateTime.now();
        
        addDomainEvent(new CustomerContactUpdatedEvent(customerId, email, phoneNumber));
    }
    
    public void updateCreditLimit(Money newCreditLimit) {
        Money oldLimit = this.creditProfile.getCreditLimit();
        this.creditProfile = this.creditProfile.updateCreditLimit(newCreditLimit);
        this.updatedAt = LocalDateTime.now();
        
        addDomainEvent(new CustomerCreditLimitUpdatedEvent(customerId, oldLimit, newCreditLimit));
    }
    
    public boolean canBorrowAmount(Money amount) {
        return creditProfile.canBorrow(amount);
    }
    
    public void reserveCredit(Money amount) {
        requirePositive(amount);
        if (!canBorrowAmount(amount)) {
            throw new InsufficientCreditException(
                String.format("Customer %s has insufficient credit. Requested: %s, Available: %s",
                    customerId, amount, creditProfile.getAvailableCredit()));
        }
        this.creditProfile = this.creditProfile.reserveCredit(amount);
        this.updatedAt = LocalDateTime.now();
        
        addDomainEvent(new CustomerCreditReservedEvent(customerId, amount));
    }
    
    /**
     * Reserves credit for the purpose the reservation names (its reference)
     * and returns the reservation with the amount added. Nothing changes if
     * the credit is insufficient.
     */
    public CreditReservation reserveCredit(Money amount, CreditReservation reservation) {
        requireOwn(reservation);
        requirePositive(amount);
        CreditReservation updated = reservation.reserve(amount);
        reserveCredit(amount);
        return updated;
    }

    /**
     * Releases credit from the reservation the caller named. Partial releases
     * are allowed; more than the reservation still holds is refused, even if
     * used credit would cover it, so a cancel can never free another
     * reservation's credit.
     *
     * @return the reservation with the amount released
     * @throws ReleaseExceedsReservationException if the amount is more than the reservation still holds
     */
    public CreditReservation releaseCredit(Money amount, CreditReservation reservation) {
        requireOwn(reservation);
        requirePositive(amount);
        CreditReservation updated = reservation.release(amount);
        applyRelease(amount);
        return updated;
    }

    /**
     * Releases credit no reservation accounts for: balances migrated from the
     * monolith and credit reserved without a reference. At most used credit
     * minus every open reservation; nothing floors at zero.
     *
     * @param openReservations what all this customer's reservations still hold
     * @throws ReservationNotFoundException if the amount is more than the untracked used credit
     */
    public void releaseUntrackedCredit(Money amount, Money openReservations) {
        requirePositive(amount);
        Objects.requireNonNull(openReservations, "Open reservations cannot be null");
        Money untracked = creditProfile.getUsedCredit().subtract(openReservations);
        if (untracked.isNegative()) {
            untracked = Money.zero(untracked.getCurrency());
        }
        if (amount.compareTo(untracked) > 0) {
            throw new ReservationNotFoundException(amount, untracked);
        }
        applyRelease(amount);
    }

    private void applyRelease(Money amount) {
        this.creditProfile = this.creditProfile.releaseCredit(amount);
        this.updatedAt = LocalDateTime.now();

        addDomainEvent(new CustomerCreditReleasedEvent(customerId, amount));
    }

    private void requireOwn(CreditReservation reservation) {
        Objects.requireNonNull(reservation, "Reservation cannot be null");
        if (!reservation.customerId().equals(customerId)) {
            throw new IllegalArgumentException("The reservation belongs to another customer");
        }
    }
    
    private void requirePositive(Money amount) {
        Objects.requireNonNull(amount, "Credit amount cannot be null");
        if (!amount.getCurrency().equals(creditProfile.getCreditLimit().getCurrency())) {
            throw new CreditCurrencyMismatchException(creditProfile.getCreditLimit().getCurrency(), amount.getCurrency());
        }
        if (amount.isZero() || amount.isNegative()) {
            throw new IllegalArgumentException("Credit amount must be positive");
        }
    }

    public void updateCreditScore(Integer newCreditScore) {
        this.creditScore = validateCreditScore(newCreditScore);
        this.creditProfile = CreditProfile.create(calculateCreditLimit(), this.creditProfile.getUsedCredit());
        this.updatedAt = LocalDateTime.now();
        
        addDomainEvent(new CustomerCreditScoreUpdatedEvent(customerId, newCreditScore));
    }
    
    public boolean isEligibleForLoan(Money loanAmount) {
        return creditScore != null && creditScore >= MIN_CREDIT_SCORE_FOR_LOAN && 
               canBorrowAmount(loanAmount);
    }
}