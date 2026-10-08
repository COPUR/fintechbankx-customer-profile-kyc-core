package com.bank.customer.application;

import com.bank.customer.application.dto.CreateCustomerRequestWithCreditScore;
import com.bank.customer.domain.CreditMovement;
import com.bank.customer.domain.Customer;
import com.bank.customer.domain.CustomerNotFoundException;
import com.bank.customer.domain.IdempotencyKeyConflictException;
import com.bank.customer.domain.port.in.CreditMovementCommand;
import com.bank.customer.domain.port.in.CreditPosition;
import com.bank.customer.domain.port.in.CustomerProfile;
import com.bank.customer.domain.port.in.GetCreditPositionUseCase;
import com.bank.customer.domain.port.in.GetCustomerProfileUseCase;
import com.bank.customer.domain.port.in.MoveCreditUseCase;
import com.bank.customer.domain.port.in.RegisterCustomerCommand;
import com.bank.customer.domain.port.in.RegisterCustomerUseCase;
import com.bank.customer.domain.port.in.UpdateCreditLimitUseCase;
import com.bank.customer.domain.port.out.CreditMovementJournal;
import com.bank.customer.domain.port.out.CustomerEventPublisher;
import com.bank.customer.domain.port.out.CustomerRepository;
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
 * Customer use cases (domain.port.in). Each loads the aggregate through an
 * out-port, calls one aggregate method, saves, and publishes the aggregate's
 * events through the outbox in the same transaction.
 *
 * Functional requirements: FR-001 registration, FR-002 profile lookup,
 * FR-003 credit limit and credit movements.
 */
@Service
@Transactional
public class CustomerManagementService implements RegisterCustomerUseCase, GetCustomerProfileUseCase,
        GetCreditPositionUseCase, UpdateCreditLimitUseCase, MoveCreditUseCase {

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

    /** FR-001: register a customer with an assigned credit limit. */
    @Override
    public CustomerProfile registerCustomer(RegisterCustomerCommand command) {
        if (customerRepository.existsByEmail(command.email())) {
            throw new IllegalArgumentException("Customer with email " + command.email() + " already exists");
        }
        Customer customer = Customer.create(
            CustomerId.generate(),
            command.firstName(),
            command.lastName(),
            command.email(),
            command.phoneNumber(),
            command.initialCreditLimit());
        return CustomerProfile.of(saveAndPublish(customer));
    }

    /** FR-002: full profile. */
    @Override
    @Transactional(readOnly = true)
    public CustomerProfile getCustomerProfile(CustomerId customerId) {
        return CustomerProfile.of(load(customerId));
    }

    /** Credit position only, for service callers that must not see personal data. */
    @Override
    @Transactional(readOnly = true)
    public CreditPosition getCreditPosition(CustomerId customerId) {
        return CreditPosition.of(load(customerId));
    }

    /** FR-003: change the credit limit. */
    @Override
    public CustomerProfile updateCreditLimit(CustomerId customerId, Money newCreditLimit) {
        Customer customer = load(customerId);
        customer.updateCreditLimit(newCreditLimit);
        return CustomerProfile.of(saveAndPublish(customer));
    }

    /** FR-003: reserve credit once per idempotency key. */
    @Override
    public CreditPosition reserveCredit(CreditMovementCommand command) {
        return CreditPosition.of(moveCredit(CreditMovement.Type.RESERVE, command));
    }

    /** FR-003: release reserved credit once per idempotency key. */
    @Override
    public CreditPosition releaseCredit(CreditMovementCommand command) {
        return CreditPosition.of(moveCredit(CreditMovement.Type.RELEASE, command));
    }

    private Customer moveCredit(CreditMovement.Type type, CreditMovementCommand command) {
        Customer customer = load(command.customerId());

        Optional<CreditMovement> previous = creditMovements.find(command.customerId(), command.idempotencyKey());
        if (previous.isPresent()) {
            if (!previous.get().sameInstruction(type, command.amount())) {
                throw IdempotencyKeyConflictException.forKey(command.idempotencyKey());
            }
            return customer;
        }

        if (type == CreditMovement.Type.RESERVE) {
            customer.reserveCredit(command.amount());
        } else {
            customer.releaseCredit(command.amount());
        }
        Customer saved = saveAndPublish(customer);
        creditMovements.record(new CreditMovement(UUID.randomUUID(), command.customerId(), command.idempotencyKey(),
            type, command.amount(), command.reference(), clock.instant()));
        return saved;
    }

    /**
     * Archive business logic, no inbound adapter yet: create a customer whose
     * limit is derived from credit score and monthly income.
     */
    public CustomerProfile createCustomerWithCreditScore(CreateCustomerRequestWithCreditScore request) {
        request.validate();
        if (customerRepository.existsByEmail(request.email())) {
            throw new IllegalArgumentException("Customer with email " + request.email() + " already exists");
        }
        Customer customer = Customer.createWithCreditScore(
            CustomerId.generate(),
            request.firstName(),
            request.lastName(),
            request.email(),
            request.phoneNumber(),
            request.monthlyIncome(),
            request.creditScore());
        return CustomerProfile.of(saveAndPublish(customer));
    }

    /** Archive business logic, no inbound adapter yet: loan eligibility by credit score. */
    @Transactional(readOnly = true)
    public boolean isEligibleForLoan(String customerId, Money loanAmount) {
        return load(CustomerId.of(customerId)).isEligibleForLoan(loanAmount);
    }

    /** Archive business logic, no inbound adapter yet: update the credit score. */
    public CustomerProfile updateCreditScore(String customerId, Integer newCreditScore) {
        Customer customer = load(CustomerId.of(customerId));
        customer.updateCreditScore(newCreditScore);
        return CustomerProfile.of(saveAndPublish(customer));
    }

    /** Archive business logic, no inbound adapter yet: find by e-mail. */
    @Transactional(readOnly = true)
    public CustomerProfile findCustomerByEmail(String email) {
        Customer customer = customerRepository.findByEmail(email)
            .orElseThrow(() -> new CustomerNotFoundException("Customer not found with email: " + email));
        return CustomerProfile.of(customer);
    }

    /** Archive business logic, no inbound adapter yet: update contact details. */
    public CustomerProfile updateContactInformation(String customerId, String newEmail, String newPhoneNumber) {
        Customer customer = load(CustomerId.of(customerId));
        customer.updateContactInformation(newEmail, newPhoneNumber);
        return CustomerProfile.of(saveAndPublish(customer));
    }

    private Customer load(CustomerId customerId) {
        return customerRepository.findById(customerId)
            .orElseThrow(() -> CustomerNotFoundException.withId(customerId.getValue()));
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
