package com.bank.customer.application;

import com.bank.customer.application.dto.CreateCustomerRequest;
import com.bank.customer.application.dto.CreateCustomerRequestWithCreditScore;
import com.bank.customer.application.dto.CustomerCreditResponse;
import com.bank.customer.application.dto.CustomerResponse;
import com.bank.customer.domain.CreditMovement;
import com.bank.customer.domain.Customer;
import com.bank.customer.domain.CustomerRepository;
import com.bank.customer.domain.port.out.CreditMovementJournal;
import com.bank.customer.domain.port.out.CustomerEventPublisher;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.DomainEvent;
import com.bank.shared.kernel.domain.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Application Service for Customer Management
 * 
 * Implements functional requirements:
 * - FR-001: Customer Registration
 * - FR-002: Customer Profile Management
 * - FR-003: Credit Limit Management
 * - FR-004: Customer Lookup & Search
 */
@Service
@Transactional
public class CustomerManagementService {
    
    private final CustomerRepository customerRepository;
    private final CustomerEventPublisher eventPublisher;
    private final CreditMovementJournal creditMovements;
    private final Clock clock;
    
    public CustomerManagementService(CustomerRepository customerRepository,
                                     CustomerEventPublisher eventPublisher,
                                     CreditMovementJournal creditMovements,
                                     Clock clock) {
        this.customerRepository = customerRepository;
        this.eventPublisher = eventPublisher;
        this.creditMovements = creditMovements;
        this.clock = clock;
    }
    
    /**
     * FR-001: Create a new customer
     */
    public CustomerResponse createCustomer(CreateCustomerRequest request) {
        // Validate request
        request.validate();
        
        // Check for duplicate email
        if (customerRepository.existsByEmail(request.email())) {
            throw new IllegalArgumentException("Customer with email " + request.email() + " already exists");
        }
        
        // Create customer
        Customer customer = Customer.create(
            CustomerId.generate(),
            request.firstName(),
            request.lastName(),
            request.email(),
            request.phoneNumber(),
            request.getCreditLimitAsMoney()
        );
        
        // Save customer
        Customer savedCustomer = saveAndPublish(customer);
        
        return CustomerResponse.from(savedCustomer);
    }
    
    /**
     * FR-002: Find customer by ID
     */
    @Transactional(readOnly = true)
    public CustomerResponse findCustomerById(String customerId) {
        CustomerId id = CustomerId.of(customerId);
        Customer customer = customerRepository.findById(id)
            .orElseThrow(() -> CustomerNotFoundException.withId(customerId));
        
        return CustomerResponse.from(customer);
    }
    
    /**
     * FR-003: Update customer credit limit
     */
    public CustomerResponse updateCreditLimit(String customerId, Money newCreditLimit) {
        CustomerId id = CustomerId.of(customerId);
        Customer customer = customerRepository.findById(id)
            .orElseThrow(() -> CustomerNotFoundException.withId(customerId));
        
        customer.updateCreditLimit(newCreditLimit);
        Customer savedCustomer = saveAndPublish(customer);
        
        return CustomerResponse.from(savedCustomer);
    }
    
    /**
     * FR-003: Reserve credit for a customer
     */
    public CustomerResponse reserveCredit(String customerId, Money amount) {
        return reserveCredit(customerId, amount, null);
    }
    
    /**
     * FR-003: Reserve credit, applied once per idempotency key. A retry with
     * the same key and amount returns the current state without reserving
     * again; the same key with a different instruction is a conflict.
     */
    public CustomerResponse reserveCredit(String customerId, Money amount, String idempotencyKey) {
        return reserveCredit(customerId, amount, idempotencyKey, null);
    }

    /**
     * FR-003: Reserve credit once per idempotency key, recording what it was
     * reserved for (for example the loan id) when the caller says.
     */
    public CustomerResponse reserveCredit(String customerId, Money amount, String idempotencyKey, String reference) {
        return moveCredit(customerId, CreditMovement.Type.RESERVE, amount, idempotencyKey, reference);
    }
    
    /**
     * FR-003: Release reserved credit for a customer
     */
    public CustomerResponse releaseCredit(String customerId, Money amount) {
        return releaseCredit(customerId, amount, null);
    }
    
    /**
     * FR-003: Release reserved credit, applied once per idempotency key.
     */
    public CustomerResponse releaseCredit(String customerId, Money amount, String idempotencyKey) {
        return releaseCredit(customerId, amount, idempotencyKey, null);
    }

    /**
     * FR-003: Release reserved credit once per idempotency key, with an
     * optional reference to what it was reserved for.
     */
    public CustomerResponse releaseCredit(String customerId, Money amount, String idempotencyKey, String reference) {
        return moveCredit(customerId, CreditMovement.Type.RELEASE, amount, idempotencyKey, reference);
    }

    /**
     * Credit position only, for service callers that must not see the
     * customer's personal data.
     */
    public CustomerCreditResponse findCreditPosition(String customerId) {
        return customerRepository.findById(CustomerId.of(customerId))
            .map(CustomerCreditResponse::from)
            .orElseThrow(() -> CustomerNotFoundException.withId(customerId));
    }
    
    private CustomerResponse moveCredit(String customerId, CreditMovement.Type type, Money amount,
                                        String idempotencyKey, String reference) {
        CustomerId id = CustomerId.of(customerId);
        Customer customer = customerRepository.findById(id)
            .orElseThrow(() -> CustomerNotFoundException.withId(customerId));
        
        if (idempotencyKey != null) {
            Optional<CreditMovement> previous = creditMovements.find(id, idempotencyKey);
            if (previous.isPresent()) {
                if (!previous.get().sameInstruction(type, amount)) {
                    throw IdempotencyKeyConflictException.forKey(idempotencyKey);
                }
                return CustomerResponse.from(customer);
            }
        }
        
        if (type == CreditMovement.Type.RESERVE) {
            customer.reserveCredit(amount);
        } else {
            customer.releaseCredit(amount);
        }
        Customer savedCustomer = saveAndPublish(customer);
        if (idempotencyKey != null) {
            creditMovements.record(new CreditMovement(UUID.randomUUID(), id, idempotencyKey, type, amount, reference, clock.instant()));
        }
        
        return CustomerResponse.from(savedCustomer);
    }
    
    /**
     * Archive Business Logic: Create customer with credit score and monthly income
     */
    public CustomerResponse createCustomerWithCreditScore(CreateCustomerRequestWithCreditScore request) {
        // Validate request
        request.validate();
        
        // Check for duplicate email
        if (customerRepository.existsByEmail(request.email())) {
            throw new IllegalArgumentException("Customer with email " + request.email() + " already exists");
        }
        
        // Create customer with credit score
        Customer customer = Customer.createWithCreditScore(
            CustomerId.generate(),
            request.firstName(),
            request.lastName(),
            request.email(),
            request.phoneNumber(),
            request.monthlyIncome(),
            request.creditScore()
        );
        
        // Save customer
        Customer savedCustomer = saveAndPublish(customer);
        
        return CustomerResponse.from(savedCustomer);
    }
    
    /**
     * Archive Business Logic: Check loan eligibility based on credit score
     */
    @Transactional(readOnly = true)
    public boolean isEligibleForLoan(String customerId, Money loanAmount) {
        CustomerId id = CustomerId.of(customerId);
        Customer customer = customerRepository.findById(id)
            .orElseThrow(() -> CustomerNotFoundException.withId(customerId));
        
        return customer.isEligibleForLoan(loanAmount);
    }
    
    /**
     * Archive Business Logic: Update customer credit score
     */
    public CustomerResponse updateCreditScore(String customerId, Integer newCreditScore) {
        CustomerId id = CustomerId.of(customerId);
        Customer customer = customerRepository.findById(id)
            .orElseThrow(() -> CustomerNotFoundException.withId(customerId));
        
        customer.updateCreditScore(newCreditScore);
        Customer savedCustomer = saveAndPublish(customer);
        
        return CustomerResponse.from(savedCustomer);
    }
    
    /**
     * Archive Business Logic: Find customer by email
     */
    @Transactional(readOnly = true)
    public CustomerResponse findCustomerByEmail(String email) {
        Customer customer = customerRepository.findByEmail(email)
            .orElseThrow(() -> new CustomerNotFoundException("Customer not found with email: " + email));
        
        return CustomerResponse.from(customer);
    }
    
    /**
     * Archive Business Logic: Update customer contact information
     */
    public CustomerResponse updateContactInformation(String customerId, String newEmail, String newPhoneNumber) {
        CustomerId id = CustomerId.of(customerId);
        Customer customer = customerRepository.findById(id)
            .orElseThrow(() -> CustomerNotFoundException.withId(customerId));
        
        customer.updateContactInformation(newEmail, newPhoneNumber);
        Customer savedCustomer = saveAndPublish(customer);
        
        return CustomerResponse.from(savedCustomer);
    }
    
    private Customer saveAndPublish(Customer customer) {
        List<DomainEvent> events = List.copyOf(customer.getDomainEvents());
        Customer saved = customerRepository.save(customer);
        if (!events.isEmpty()) {
            eventPublisher.publish(saved, events);
        }
        customer.clearDomainEvents();
        saved.clearDomainEvents();
        return saved;
    }
}
