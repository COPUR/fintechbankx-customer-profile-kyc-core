package com.bank.customer.application;

import com.bank.customer.application.dto.CreateCustomerRequestWithCreditScore;
import com.bank.customer.domain.CreditMovement;
import com.bank.customer.domain.Customer;
import com.bank.customer.domain.CustomerCreditReservedEvent;
import com.bank.customer.domain.CustomerNotFoundException;
import com.bank.customer.domain.IdempotencyKeyConflictException;
import com.bank.customer.domain.port.in.CreditMovementCommand;
import com.bank.customer.domain.port.in.CreditPosition;
import com.bank.customer.domain.port.in.CustomerProfile;
import com.bank.customer.domain.port.in.RegisterCustomerCommand;
import com.bank.customer.domain.port.out.CustomerRepository;
import com.bank.customer.domain.port.out.IdentityDirectoryPort;
import com.bank.customer.domain.IdentityDirectoryUnavailableException;
import com.bank.customer.domain.IdentityLinkConflictException;
import com.bank.customer.domain.IdentityUserId;
import com.bank.customer.domain.port.in.IdentityLink;
import com.bank.customer.domain.port.in.LinkIdentityCommand;
import com.bank.customer.domain.port.out.CreditMovementJournal;
import com.bank.customer.domain.port.out.CreditReservationLedger;
import com.bank.customer.domain.CreditReservation;
import com.bank.customer.domain.ReleaseExceedsReservationException;
import com.bank.customer.domain.ReservationNotFoundException;
import com.bank.customer.domain.port.out.CustomerEventPublisher;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerManagementServiceTest {

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private CustomerEventPublisher eventPublisher;

    @Mock
    private CreditMovementJournal creditMovements;

    @Mock
    private CreditReservationLedger creditReservations;

    @Mock
    private IdentityDirectoryPort identityDirectory;

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    private CustomerManagementService service;

    @BeforeEach
    void setUp() {
        service = new CustomerManagementService(customerRepository, eventPublisher, creditMovements,
            creditReservations, identityDirectory, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Customer existingCustomer() {
        Customer customer = Customer.create(CustomerId.of("CUST-IDEM"), "Ali", "Sample", "ali@example.com",
            "+971500000001", Money.aed(new BigDecimal("5000.00")));
        customer.clearDomainEvents();
        return customer;
    }

    @Test
    void reserveWithNewKeyAppliesPublishesAndRecordsTheMovement() {
        Customer customer = existingCustomer();
        when(customerRepository.findById(CustomerId.of("CUST-IDEM"))).thenReturn(Optional.of(customer));
        when(creditMovements.find(CustomerId.of("CUST-IDEM"), "key-1")).thenReturn(Optional.empty());
        when(customerRepository.save(any(Customer.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CreditPosition response = service.reserveCredit(move("CUST-IDEM", "1000.00", "key-1", "LOAN-7"));

        assertThat(response.usedCredit()).isEqualTo(aed("1000.00"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<com.bank.shared.kernel.domain.DomainEvent>> events = ArgumentCaptor.forClass(List.class);
        verify(eventPublisher).publish(eq(customer), events.capture());
        assertThat(events.getValue()).singleElement().isInstanceOf(CustomerCreditReservedEvent.class);
        ArgumentCaptor<CreditMovement> movement = ArgumentCaptor.forClass(CreditMovement.class);
        verify(creditMovements).record(movement.capture());
        assertThat(movement.getValue().type()).isEqualTo(CreditMovement.Type.RESERVE);
        assertThat(movement.getValue().idempotencyKey()).isEqualTo("key-1");
        assertThat(movement.getValue().occurredAt()).isEqualTo(NOW);
        assertThat(movement.getValue().reference()).isEqualTo("LOAN-7");
        assertThat(customer.getDomainEvents()).isEmpty();
    }

    @Test
    void retriedReserveWithSameKeyIsNotAppliedTwice() {
        Customer customer = existingCustomer();
        Money amount = Money.aed(new BigDecimal("1000.00"));
        when(customerRepository.findById(CustomerId.of("CUST-IDEM"))).thenReturn(Optional.of(customer));
        when(creditMovements.find(CustomerId.of("CUST-IDEM"), "key-1")).thenReturn(Optional.of(
            new CreditMovement(UUID.randomUUID(), CustomerId.of("CUST-IDEM"), "key-1", CreditMovement.Type.RESERVE, amount, null, NOW)));

        CreditPosition response = service.reserveCredit(move("CUST-IDEM", "1000.00", "key-1", null));

        assertThat(response.usedCredit().isZero()).isTrue();
        verify(customerRepository, never()).save(any(Customer.class));
        verify(creditMovements, never()).record(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void keyReusedForADifferentMovementIsAConflict() {
        Customer customer = existingCustomer();
        when(customerRepository.findById(CustomerId.of("CUST-IDEM"))).thenReturn(Optional.of(customer));
        when(creditMovements.find(CustomerId.of("CUST-IDEM"), "key-1")).thenReturn(Optional.of(
            new CreditMovement(UUID.randomUUID(), CustomerId.of("CUST-IDEM"), "key-1", CreditMovement.Type.RESERVE,
                Money.aed(new BigDecimal("1000.00")), null, NOW)));

        assertThatThrownBy(() -> service.releaseCredit(move("CUST-IDEM", "1000.00", "key-1", null)))
            .isInstanceOf(IdempotencyKeyConflictException.class);
        verify(customerRepository, never()).save(any(Customer.class));
    }

    @Test
    void releaseRecordsTheMovementAndPublishes() {
        Customer customer = existingCustomer();
        customer.reserveCredit(Money.aed(new BigDecimal("2000.00")));
        customer.clearDomainEvents();
        when(customerRepository.findById(CustomerId.of("CUST-IDEM"))).thenReturn(Optional.of(customer));
        when(creditMovements.find(CustomerId.of("CUST-IDEM"), "key-2")).thenReturn(Optional.empty());
        when(creditReservations.openAmount(CustomerId.of("CUST-IDEM"), AED)).thenReturn(aed("0.00"));
        when(customerRepository.save(any(Customer.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CreditPosition response = service.releaseCredit(move("CUST-IDEM", "500.00", "key-2", "LOAN-7"));

        assertThat(response.usedCredit()).isEqualTo(aed("1500.00"));
        ArgumentCaptor<CreditMovement> movement = ArgumentCaptor.forClass(CreditMovement.class);
        verify(creditMovements).record(movement.capture());
        assertThat(movement.getValue().type()).isEqualTo(CreditMovement.Type.RELEASE);
        verify(eventPublisher).publish(eq(customer), anyList());
    }

    @Test
    void aReserveWithAReferenceIsAddedToThatReservationAfterTheCustomerIsSaved() {
        Customer customer = existingCustomer();
        when(customerRepository.findById(CustomerId.of("CUST-IDEM"))).thenReturn(Optional.of(customer));
        when(creditMovements.find(CustomerId.of("CUST-IDEM"), "LOAN-7:reserve")).thenReturn(Optional.empty());
        when(creditReservations.find(CustomerId.of("CUST-IDEM"), "LOAN-7")).thenReturn(Optional.empty());
        when(customerRepository.save(any(Customer.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.reserveCredit(move("CUST-IDEM", "1000.00", "LOAN-7:reserve", "LOAN-7"));

        ArgumentCaptor<CreditReservation> saved = ArgumentCaptor.forClass(CreditReservation.class);
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(customerRepository, creditReservations);
        order.verify(customerRepository).save(customer);
        order.verify(creditReservations).save(saved.capture());
        assertThat(saved.getValue().reference()).isEqualTo("LOAN-7");
        assertThat(saved.getValue().reserved()).isEqualTo(aed("1000.00"));
        assertThat(saved.getValue().remaining()).isEqualTo(aed("1000.00"));
    }

    @Test
    void aReserveWithoutAReferenceIsNotTracked() {
        Customer customer = existingCustomer();
        when(customerRepository.findById(CustomerId.of("CUST-IDEM"))).thenReturn(Optional.of(customer));
        when(customerRepository.save(any(Customer.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.reserveCredit(move("CUST-IDEM", "1000.00", "key-u", null));

        verifyNoInteractions(creditReservations);
        assertThat(customer.getCreditProfile().getUsedCredit()).isEqualTo(aed("1000.00"));
    }

    @Test
    void aReleaseNamingAReservationTakesPartOfIt() {
        Customer customer = existingCustomer();
        customer.reserveCredit(aed("2000.00"));
        customer.clearDomainEvents();
        CreditReservation loan7 = CreditReservation.none(CustomerId.of("CUST-IDEM"), "LOAN-7", AED).reserve(aed("2000.00"));
        when(customerRepository.findById(CustomerId.of("CUST-IDEM"))).thenReturn(Optional.of(customer));
        when(creditReservations.find(CustomerId.of("CUST-IDEM"), "LOAN-7")).thenReturn(Optional.of(loan7));
        when(customerRepository.save(any(Customer.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CreditPosition response = service.releaseCredit(move("CUST-IDEM", "500.00", "LOAN-7:cancel", "LOAN-7"));

        assertThat(response.usedCredit()).isEqualTo(aed("1500.00"));
        ArgumentCaptor<CreditReservation> saved = ArgumentCaptor.forClass(CreditReservation.class);
        verify(creditReservations).save(saved.capture());
        assertThat(saved.getValue().remaining()).isEqualTo(aed("1500.00"));
        verify(creditReservations, never()).openAmount(any(), any());
        verify(creditMovements).record(any(CreditMovement.class));
    }

    @Test
    void aReleaseAboveTheNamedReservationIsRefusedAndNothingIsSaved() {
        Customer customer = existingCustomer();
        customer.reserveCredit(aed("3000.00"));
        customer.clearDomainEvents();
        CreditReservation loan7 = CreditReservation.none(CustomerId.of("CUST-IDEM"), "LOAN-7", AED).reserve(aed("2000.00"));
        when(customerRepository.findById(CustomerId.of("CUST-IDEM"))).thenReturn(Optional.of(customer));
        when(creditReservations.find(CustomerId.of("CUST-IDEM"), "LOAN-7")).thenReturn(Optional.of(loan7));

        assertThatThrownBy(() -> service.releaseCredit(move("CUST-IDEM", "2000.01", "LOAN-7:cancel", "LOAN-7")))
            .isInstanceOf(ReleaseExceedsReservationException.class);

        verify(customerRepository, never()).save(any(Customer.class));
        verify(creditReservations, never()).save(any());
        verify(creditMovements, never()).record(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void aReleaseNamingNoReservationTakesOnlyUntrackedCredit() {
        Customer customer = existingCustomer();
        customer.reserveCredit(aed("5000.00"));
        customer.clearDomainEvents();
        when(customerRepository.findById(CustomerId.of("CUST-IDEM"))).thenReturn(Optional.of(customer));
        when(creditReservations.find(CustomerId.of("CUST-IDEM"), "LOAN-UNKNOWN")).thenReturn(Optional.empty());
        when(creditReservations.openAmount(CustomerId.of("CUST-IDEM"), AED)).thenReturn(aed("3000.00"));

        assertThatThrownBy(() -> service.releaseCredit(move("CUST-IDEM", "2000.01", "key-x", "LOAN-UNKNOWN")))
            .isInstanceOf(ReservationNotFoundException.class);
        assertThatThrownBy(() -> service.releaseCredit(move("CUST-IDEM", "2000.01", "key-y", null)))
            .isInstanceOf(ReservationNotFoundException.class);
        verify(customerRepository, never()).save(any(Customer.class));

        when(customerRepository.save(any(Customer.class))).thenAnswer(invocation -> invocation.getArgument(0));
        CreditPosition response = service.releaseCredit(move("CUST-IDEM", "2000.00", "key-z", "LOAN-UNKNOWN"));

        assertThat(response.usedCredit()).isEqualTo(aed("3000.00"));
        verify(creditReservations, never()).save(any());
    }

    /** Idempotency is unchanged: a replayed release answers before any reservation rule runs. */
    @Test
    void aReplayedReleaseIsAnsweredFromTheJournalWithoutTouchingReservations() {
        Customer customer = existingCustomer();
        when(customerRepository.findById(CustomerId.of("CUST-IDEM"))).thenReturn(Optional.of(customer));
        when(creditMovements.find(CustomerId.of("CUST-IDEM"), "LOAN-7:release")).thenReturn(Optional.of(
            new CreditMovement(UUID.randomUUID(), CustomerId.of("CUST-IDEM"), "LOAN-7:release", CreditMovement.Type.RELEASE,
                aed("500.00"), "LOAN-7", NOW)));

        CreditPosition response = service.releaseCredit(move("CUST-IDEM", "500.00", "LOAN-7:release", "LOAN-7"));

        assertThat(response.usedCredit().isZero()).isTrue();
        verifyNoInteractions(creditReservations, eventPublisher);
        verify(customerRepository, never()).save(any(Customer.class));
    }

    @Test
    void profileToleratesLegacyCustomerWithoutContactDetailsOrTimestamps() {
        Customer legacy = Customer.rehydrate(new com.bank.customer.domain.CustomerSnapshot(
            CustomerId.of("7"), "Legacy", "Customer", null, null,
            Money.aed(new BigDecimal("1000.00")), Money.aed(BigDecimal.ZERO), null, null, null, null, 0L));
        when(customerRepository.findById(CustomerId.of("7"))).thenReturn(Optional.of(legacy));

        CustomerProfile response = service.getCustomerProfile(CustomerId.of("7"));

        assertThat(response.email()).isNull();
        assertThat(response.createdAt()).isNull();
        assertThat(response.credit().availableCredit()).isEqualTo(aed("1000.00"));
    }

    @Test
    void creditLimitChangeIsSavedAndPublished() {
        Customer customer = existingCustomer();
        when(customerRepository.findById(CustomerId.of("CUST-IDEM"))).thenReturn(Optional.of(customer));
        when(customerRepository.save(any(Customer.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CustomerProfile response = service.updateCreditLimit(CustomerId.of("CUST-IDEM"), aed("7000.00"));

        assertThat(response.credit().creditLimit()).isEqualTo(aed("7000.00"));
        verify(eventPublisher).publish(eq(customer), anyList());
    }

    @Test
    void createCustomerShouldPersistAndReturnResponse() {
        RegisterCustomerCommand request = registration();
        when(customerRepository.existsByEmail("ali@example.com")).thenReturn(false);
        when(customerRepository.save(any(Customer.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CustomerProfile response = service.registerCustomer(request);

        assertThat(response.email()).isEqualTo("ali@example.com");
        assertThat(response.firstName()).isEqualTo("Ali");
        assertThat(response.credit().creditLimit()).isEqualTo(aed("5000.00"));
        verify(customerRepository).save(any(Customer.class));
    }

    @Test
    void createCustomerShouldRejectDuplicateEmail() {
        RegisterCustomerCommand request = registration();
        when(customerRepository.existsByEmail("ali@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.registerCustomer(request))
            .isInstanceOf(com.bank.customer.domain.CustomerAlreadyExistsException.class)
            .hasMessageNotContaining("ali@example.com");

        verify(customerRepository, never()).save(any(Customer.class));
    }

    @Test
    void findCustomerByIdShouldThrowWhenNotFound() {
        when(customerRepository.findById(any(CustomerId.class))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCustomerProfile(CustomerId.of("CUST-MISSING")))
            .isInstanceOf(CustomerNotFoundException.class)
            .hasMessageContaining("CUST-MISSING");
    }

    @Test
    void reserveAndReleaseCreditShouldPersistChanges() {
        Customer customer = customer();
        when(customerRepository.findById(any(CustomerId.class))).thenReturn(Optional.of(customer));
        doReturn(customer).when(customerRepository).save(customer);
        when(creditReservations.openAmount(customer.getId(), AED)).thenReturn(aed("0.00"));

        service.reserveCredit(move(customer.getId().getValue(), "1000.00", "key-r", null));
        service.releaseCredit(move(customer.getId().getValue(), "500.00", "key-l", null));

        assertThat(customer.getCreditProfile().getUsedCredit()).isEqualTo(Money.aed(new BigDecimal("500.00")));
        verify(customerRepository, times(2)).save(customer);
    }

    @Test
    void createCustomerWithCreditScoreShouldPersistAndReturnResponse() {
        CreateCustomerRequestWithCreditScore request = new CreateCustomerRequestWithCreditScore(
            "Lina",
            "Hassan",
            "lina@example.com",
            "+971500000002",
            Money.aed(new BigDecimal("2000.00")),
            750
        );
        when(customerRepository.existsByEmail("lina@example.com")).thenReturn(false);
        when(customerRepository.save(any(Customer.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CustomerProfile response = service.createCustomerWithCreditScore(request);

        assertThat(response.creditScore()).isEqualTo(750);
        assertThat(response.monthlyIncome()).isEqualTo(aed("2000.00"));
        assertThat(response.credit().creditLimit()).isEqualTo(aed("10000.00"));
    }

    @Test
    void updateCreditScoreShouldPersistUpdatedCustomer() {
        Customer customer = Customer.createWithCreditScore(
            CustomerId.of("CUST-APP-001"),
            "Sara",
            "Noor",
            "sara@example.com",
            "+971500000003",
            Money.aed(new BigDecimal("2000.00")),
            650
        );
        when(customerRepository.findById(any(CustomerId.class))).thenReturn(Optional.of(customer));
        doReturn(customer).when(customerRepository).save(customer);

        CustomerProfile response = service.updateCreditScore(customer.getId().getValue(), 780);

        assertThat(response.creditScore()).isEqualTo(780);
        assertThat(response.credit().creditLimit()).isEqualTo(aed("10000.00"));
        verify(customerRepository).save(customer);
    }

    @Test
    void findCustomerByEmailShouldThrowWhenNotFound() {
        when(customerRepository.findByEmail("missing@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findCustomerByEmail("missing@example.com"))
            .isInstanceOf(CustomerNotFoundException.class)
            .hasMessageContaining("missing@example.com");
    }

    @Test
    void creditPositionCarriesNoPersonalData() {
        Customer customer = existingCustomer();
        customer.reserveCredit(Money.aed(new BigDecimal("1000.00")));
        when(customerRepository.findById(CustomerId.of("CUST-IDEM"))).thenReturn(Optional.of(customer));

        CreditPosition credit = service.getCreditPosition(CustomerId.of("CUST-IDEM"));

        assertThat(credit.customerId()).isEqualTo(CustomerId.of("CUST-IDEM"));
        assertThat(credit.usedCredit()).isEqualTo(aed("1000.00"));
        assertThat(credit.availableCredit()).isEqualTo(aed("4000.00"));
        assertThat(CreditPosition.class.getRecordComponents())
            .extracting(java.lang.reflect.RecordComponent::getName)
            .containsExactly("customerId", "creditLimit", "usedCredit", "availableCredit");
    }

    @Test
    void creditPositionOfAnUnknownCustomerIsNotFound() {
        when(customerRepository.findById(CustomerId.of("CUST-NONE"))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCreditPosition(CustomerId.of("CUST-NONE"))).isInstanceOf(CustomerNotFoundException.class);
    }

    @Test
    void isEligibleForLoanShouldDelegateToAggregateRules() {
        Customer customer = Customer.createWithCreditScore(
            CustomerId.of("CUST-APP-002"),
            "Nora",
            "Khan",
            "nora@example.com",
            "+971500000004",
            Money.aed(new BigDecimal("2500.00")),
            700
        );
        when(customerRepository.findById(any(CustomerId.class))).thenReturn(Optional.of(customer));

        boolean eligible = service.isEligibleForLoan(customer.getId().getValue(), Money.aed(new BigDecimal("4000.00")));

        assertThat(eligible).isTrue();
    }

    @Test
    void updateContactInformationShouldPersistAndReturnUpdatedResponse() {
        Customer customer = customer();
        when(customerRepository.findById(any(CustomerId.class))).thenReturn(Optional.of(customer));
        doReturn(customer).when(customerRepository).save(customer);

        CustomerProfile response = service.updateContactInformation(
            customer.getId().getValue(),
            "new-email@example.com",
            "+971500009999"
        );

        assertThat(response.email()).isEqualTo("new-email@example.com");
        assertThat(response.phoneNumber()).isEqualTo("+971500009999");
        verify(customerRepository).save(customer);
    }

    private static final String USER = "6f1c2a7e-5b8d-4c3e-9a1f-0d2b3c4e5f60";

    @Test
    void linkingStoresTheLinkAndSetsTheCustomerIdOnTheIdentityUser() {
        Customer customer = existingCustomer();
        when(customerRepository.findById(CustomerId.of("CUST-IDEM"))).thenReturn(Optional.of(customer));
        when(customerRepository.save(any(Customer.class))).thenAnswer(invocation -> invocation.getArgument(0));

        IdentityLink link = service.linkIdentity(new LinkIdentityCommand(CustomerId.of("CUST-IDEM"), new IdentityUserId(USER)));

        assertThat(link).isEqualTo(new IdentityLink(CustomerId.of("CUST-IDEM"), new IdentityUserId(USER)));
        var order = org.mockito.Mockito.inOrder(customerRepository, identityDirectory);
        order.verify(customerRepository).save(customer);
        order.verify(identityDirectory).linkCustomer(new IdentityUserId(USER), CustomerId.of("CUST-IDEM"));
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void relinkingTheSameUserOnlyRepairsTheIdentityAttribute() {
        Customer customer = existingCustomer();
        customer.linkIdentity(new IdentityUserId(USER));
        when(customerRepository.findById(CustomerId.of("CUST-IDEM"))).thenReturn(Optional.of(customer));

        service.linkIdentity(new LinkIdentityCommand(CustomerId.of("CUST-IDEM"), new IdentityUserId(USER)));

        verify(customerRepository, never()).save(any(Customer.class));
        verify(identityDirectory).linkCustomer(new IdentityUserId(USER), CustomerId.of("CUST-IDEM"));
    }

    @Test
    void aCustomerLinkedToAnotherUserIsRefusedBeforeTheDirectoryIsTouched() {
        Customer customer = existingCustomer();
        customer.linkIdentity(new IdentityUserId("someone-else"));
        when(customerRepository.findById(CustomerId.of("CUST-IDEM"))).thenReturn(Optional.of(customer));

        assertThatThrownBy(() -> service.linkIdentity(
                new LinkIdentityCommand(CustomerId.of("CUST-IDEM"), new IdentityUserId(USER))))
            .isInstanceOf(IdentityLinkConflictException.class);
        verifyNoInteractions(identityDirectory);
        verify(customerRepository, never()).save(any(Customer.class));
    }

    @Test
    void aDirectoryFailureFailsTheLinkSoTheTransactionRollsBack() {
        Customer customer = existingCustomer();
        when(customerRepository.findById(CustomerId.of("CUST-IDEM"))).thenReturn(Optional.of(customer));
        when(customerRepository.save(any(Customer.class))).thenAnswer(invocation -> invocation.getArgument(0));
        org.mockito.Mockito.doThrow(new IdentityDirectoryUnavailableException("down"))
            .when(identityDirectory).linkCustomer(any(), any());

        assertThatThrownBy(() -> service.linkIdentity(
                new LinkIdentityCommand(CustomerId.of("CUST-IDEM"), new IdentityUserId(USER))))
            .isInstanceOf(IdentityDirectoryUnavailableException.class);
    }

    @Test
    void linkingAnUnknownCustomerIsNotFound() {
        when(customerRepository.findById(CustomerId.of("CUST-NONE"))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.linkIdentity(
                new LinkIdentityCommand(CustomerId.of("CUST-NONE"), new IdentityUserId(USER))))
            .isInstanceOf(CustomerNotFoundException.class);
        verifyNoInteractions(identityDirectory);
    }

    private static final java.util.Currency AED = java.util.Currency.getInstance("AED");

    private static Money aed(String amount) {
        return Money.aed(new BigDecimal(amount));
    }

    private static CreditMovementCommand move(String customerId, String amount, String key, String reference) {
        return new CreditMovementCommand(CustomerId.of(customerId), aed(amount), key, reference);
    }

    private static RegisterCustomerCommand registration() {
        return new RegisterCustomerCommand("Ali", "Sample", "ali@example.com", "+971500000001", aed("5000.00"));
    }

    private static Customer customer() {
        return Customer.create(
            CustomerId.of("CUST-APP-BASE"),
            "Ali",
            "Sample",
            "ali@example.com",
            "+971500000001",
            Money.aed(new BigDecimal("10000.00"))
        );
    }

    @Test
    void staffChangeTheKycStatusAndTheChangeIsPublished() {
        Customer customer = existingCustomer();
        when(customerRepository.findById(CustomerId.of("CUST-IDEM"))).thenReturn(Optional.of(customer));
        when(customerRepository.save(any(Customer.class))).thenAnswer(invocation -> invocation.getArgument(0));

        com.bank.customer.domain.port.in.KycStatusView view = service.changeKycStatus(
            new com.bank.customer.domain.port.in.ChangeKycStatusCommand(CustomerId.of("CUST-IDEM"),
                com.bank.customer.domain.KycStatus.Status.VERIFIED, "banker-sub-1"));

        assertThat(view.customerId()).isEqualTo(CustomerId.of("CUST-IDEM"));
        assertThat(view.kycStatus().status()).isEqualTo(com.bank.customer.domain.KycStatus.Status.VERIFIED);
        assertThat(view.kycStatus().verifiedAt()).isEqualTo(NOW);
        assertThat(view.kycStatus().updatedBy()).isEqualTo("banker-sub-1");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<com.bank.shared.kernel.domain.DomainEvent>> events = ArgumentCaptor.forClass(List.class);
        verify(eventPublisher).publish(eq(customer), events.capture());
        assertThat(events.getValue()).singleElement()
            .isInstanceOf(com.bank.customer.domain.CustomerKycStatusChangedEvent.class);
    }

    @Test
    void anUnchangedKycStatusIsNotSaved() {
        Customer customer = existingCustomer();
        customer.verifyKyc("banker-sub-1", NOW);
        customer.clearDomainEvents();
        when(customerRepository.findById(CustomerId.of("CUST-IDEM"))).thenReturn(Optional.of(customer));

        service.changeKycStatus(new com.bank.customer.domain.port.in.ChangeKycStatusCommand(CustomerId.of("CUST-IDEM"),
            com.bank.customer.domain.KycStatus.Status.VERIFIED, "banker-sub-2"));

        verify(customerRepository, never()).save(any(Customer.class));
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void pendingIsNotAStaffDecision() {
        assertThatThrownBy(() -> new com.bank.customer.domain.port.in.ChangeKycStatusCommand(CustomerId.of("CUST-IDEM"),
                com.bank.customer.domain.KycStatus.Status.PENDING, "banker-sub-1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Staff set the KYC status to VERIFIED or REJECTED");
    }

    @Test
    void theKycStatusIsReadWithoutPersonalData() {
        Customer customer = existingCustomer();
        when(customerRepository.findById(CustomerId.of("CUST-IDEM"))).thenReturn(Optional.of(customer));

        com.bank.customer.domain.port.in.KycStatusView view = service.getKycStatus(CustomerId.of("CUST-IDEM"));

        assertThat(view).isEqualTo(new com.bank.customer.domain.port.in.KycStatusView(CustomerId.of("CUST-IDEM"),
            com.bank.customer.domain.KycStatus.pending()));
        assertThat(view.kycVerified()).isFalse();
        when(customerRepository.findById(CustomerId.of("CUST-NONE"))).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getKycStatus(CustomerId.of("CUST-NONE")))
            .isInstanceOf(com.bank.customer.domain.CustomerNotFoundException.class);
    }
}
