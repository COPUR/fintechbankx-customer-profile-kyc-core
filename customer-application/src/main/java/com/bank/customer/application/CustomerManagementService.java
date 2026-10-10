package com.bank.customer.application;

import com.bank.customer.application.dto.CreateCustomerRequestWithCreditScore;
import com.bank.customer.domain.CreditMovement;
import com.bank.customer.domain.Customer;
import com.bank.customer.domain.CustomerAlreadyExistsException;
import com.bank.customer.domain.CustomerNotFoundException;
import com.bank.customer.domain.IdempotencyKeyConflictException;
import com.bank.customer.domain.KycStatus;
import com.bank.customer.domain.port.in.ChangeKycStatusCommand;
import com.bank.customer.domain.port.in.ChangeKycStatusUseCase;
import com.bank.customer.domain.port.in.CreditMovementCommand;
import com.bank.customer.domain.port.in.GetKycStatusUseCase;
import com.bank.customer.domain.port.in.KycStatusView;
import com.bank.customer.domain.port.in.CreditPosition;
import com.bank.customer.domain.port.in.CustomerProfile;
import com.bank.customer.domain.port.in.GetCreditPositionUseCase;
import com.bank.customer.domain.port.in.GetCustomerProfileUseCase;
import com.bank.customer.domain.port.in.IdentityLink;
import com.bank.customer.domain.port.in.LinkIdentityCommand;
import com.bank.customer.domain.port.in.LinkIdentityUseCase;
import com.bank.customer.domain.port.in.MoveCreditUseCase;
import com.bank.customer.domain.port.in.RegisterCustomerCommand;
import com.bank.customer.domain.port.in.RegisterCustomerUseCase;
import com.bank.customer.domain.port.in.UpdateCreditLimitUseCase;
import com.bank.customer.domain.port.out.CreditMovementJournal;
import com.bank.customer.domain.port.out.CreditReservationLedger;
import com.bank.customer.domain.CreditReservation;
import com.bank.customer.domain.ReservationNotFoundException;
import com.bank.customer.domain.port.out.CustomerEventPublisher;
import com.bank.customer.domain.port.out.CustomerRepository;
import com.bank.customer.domain.port.out.IdentityDirectoryPort;
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
        GetCreditPositionUseCase, UpdateCreditLimitUseCase, MoveCreditUseCase, LinkIdentityUseCase,
        GetKycStatusUseCase, ChangeKycStatusUseCase {

    private final CustomerRepository customerRepository;
    private final CustomerEventPublisher eventPublisher;
    private final CreditMovementJournal creditMovements;
    private final CreditReservationLedger creditReservations;
    private final IdentityDirectoryPort identityDirectory;
    private final Clock clock;

    public CustomerManagementService(CustomerRepository customerRepository,
                                     CustomerEventPublisher eventPublisher,
                                     CreditMovementJournal creditMovements,
                                     CreditReservationLedger creditReservations,
                                     IdentityDirectoryPort identityDirectory,
                                     Clock clock) {
        this.customerRepository = customerRepository;
        this.eventPublisher = eventPublisher;
        this.creditMovements = creditMovements;
        this.creditReservations = creditReservations;
        this.identityDirectory = identityDirectory;
        this.clock = clock;
    }

    /** FR-001: register a customer with an assigned credit limit. */
    @Override
    public CustomerProfile registerCustomer(RegisterCustomerCommand command) {
        if (customerRepository.existsByEmail(command.email())) {
            throw new CustomerAlreadyExistsException();
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

    /**
     * Onboarding: link the customer to the end user's identity account. The
     * link is saved first, so a user already linked to another customer is
     * refused by the database before the directory is touched; then the
     * directory sets customer_id on the user. If the directory fails, the
     * exception rolls the link back. Relinking the same user only repeats the
     * (idempotent) directory call, which repairs a missing attribute.
     */
    @Override
    public IdentityLink linkIdentity(LinkIdentityCommand command) {
        Customer customer = load(command.customerId());
        if (customer.linkIdentity(command.identityUserId())) {
            customerRepository.save(customer);
        }
        identityDirectory.linkCustomer(command.identityUserId(), command.customerId());
        return new IdentityLink(command.customerId(), command.identityUserId());
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

    /** KYC status only, for service callers (payments) that must not see personal data. */
    @Override
    @Transactional(readOnly = true)
    public KycStatusView getKycStatus(CustomerId customerId) {
        return KycStatusView.of(load(customerId));
    }

    /** Staff verify or reject; an unchanged status is neither saved nor published. */
    @Override
    public KycStatusView changeKycStatus(ChangeKycStatusCommand command) {
        Customer customer = load(command.customerId());
        boolean changed = command.status() == KycStatus.Status.VERIFIED
            ? customer.verifyKyc(command.updatedBy(), clock.instant())
            : customer.rejectKyc(command.updatedBy(), clock.instant());
        return KycStatusView.of(changed ? saveAndPublish(customer) : customer);
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
        return moveCredit(CreditMovement.Type.RESERVE, command);
    }

    /**
     * FR-003: release reserved credit once per idempotency key. A release
     * whose reference names a reservation that still holds credit takes at
     * most what it holds (more is RELEASE_EXCEEDS_RESERVATION); a release
     * whose reference names a reservation that holds nothing any more, or
     * matches no reservation at all, is always refused with
     * RESERVATION_NOT_FOUND, whatever the amount (loan's sweep relies on that
     * answer); only a release without a reference takes the untracked used
     * credit, at most.
     */
    @Override
    public CreditPosition releaseCredit(CreditMovementCommand command) {
        return moveCredit(CreditMovement.Type.RELEASE, command);
    }

    /**
     * Applies the movement once per idempotency key and answers with the
     * position it left behind. A replay (same key, same instruction) answers
     * with the position recorded for the original movement (V12), so the
     * caller gets the same answer as the first call whatever has moved since;
     * movements journalled before V12 carry no position and answer with the
     * current one.
     */
    private CreditPosition moveCredit(CreditMovement.Type type, CreditMovementCommand command) {
        Customer customer = load(command.customerId());

        Optional<CreditMovement> previous = creditMovements.find(command.customerId(), command.idempotencyKey());
        if (previous.isPresent()) {
            if (!previous.get().sameInstruction(type, command.amount(), command.reference())) {
                throw IdempotencyKeyConflictException.forKey(command.idempotencyKey());
            }
            return previous.get().position()
                .map(position -> CreditPosition.of(customer.getId(), position))
                .orElseGet(() -> CreditPosition.of(customer));
        }

        Optional<CreditReservation> reservation = command.reference() == null
            ? Optional.empty()
            : creditReservations.find(command.customerId(), command.reference());
        Optional<CreditReservation> changed = Optional.empty();
        if (type == CreditMovement.Type.RESERVE) {
            if (command.reference() == null) {
                customer.reserveCredit(command.amount());
            } else {
                CreditReservation open = reservation.orElseGet(() ->
                    CreditReservation.none(command.customerId(), command.reference(), creditCurrency(customer)));
                changed = Optional.of(customer.reserveCredit(command.amount(), open));
            }
        } else if (reservation.isPresent() && reservation.get().remaining().isPositive()) {
            changed = Optional.of(customer.releaseCredit(command.amount(), reservation.get()));
        } else if (reservation.isPresent()) {
            // Fully released already: the same answer as an unknown reference, so loan's sweep can
            // tell "nothing left under this loan id" from "released too much".
            throw ReservationNotFoundException.forSettledReservation(command.amount());
        } else if (command.reference() != null) {
            // An unknown loan id can never touch migrated or unreferenced credit (loan auto-release).
            throw ReservationNotFoundException.forUnknownReference(command.amount());
        } else {
            customer.releaseUntrackedCredit(command.amount(),
                creditReservations.openAmount(command.customerId(), creditCurrency(customer)));
        }
        // The customer save checks the optimistic version first, so a reservation is only written by the
        // transaction that won the race on the customer row.
        Customer saved = saveAndPublish(customer);
        changed.ifPresent(creditReservations::save);
        creditMovements.record(new CreditMovement(UUID.randomUUID(), command.customerId(), command.idempotencyKey(),
            type, command.amount(), command.reference(), clock.instant(), saved.getCreditProfile()));
        return CreditPosition.of(saved);
    }

    /**
     * Archive business logic, no inbound adapter yet: create a customer whose
     * limit is derived from credit score and monthly income.
     */
    public CustomerProfile createCustomerWithCreditScore(CreateCustomerRequestWithCreditScore request) {
        request.validate();
        if (customerRepository.existsByEmail(request.email())) {
            throw new CustomerAlreadyExistsException();
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

    private static java.util.Currency creditCurrency(Customer customer) {
        return customer.getCreditProfile().getCreditLimit().getCurrency();
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
