package com.bank.customer.infrastructure.web;

import com.bank.customer.domain.Customer;
import com.bank.customer.domain.port.in.CreditMovementCommand;
import com.bank.customer.domain.port.in.CreditPosition;
import com.bank.customer.domain.port.in.CustomerProfile;
import com.bank.customer.domain.port.in.CreditStanding;
import com.bank.customer.domain.port.in.GetCreditPositionUseCase;
import com.bank.customer.domain.port.in.GetCreditStandingUseCase;
import com.bank.customer.domain.port.in.GetScreeningProfileUseCase;
import com.bank.customer.domain.port.in.ScreeningProfile;
import com.bank.customer.infrastructure.web.dto.CreditStandingResponse;
import com.bank.customer.infrastructure.web.dto.ScreeningProfileResponse;
import com.bank.customer.domain.port.in.GetCustomerProfileUseCase;
import com.bank.customer.domain.port.in.MoveCreditUseCase;
import com.bank.customer.domain.port.in.RegisterCustomerCommand;
import com.bank.customer.domain.port.in.RegisterCustomerUseCase;
import com.bank.customer.domain.port.in.UpdateCreditLimitUseCase;
import com.bank.customer.infrastructure.web.dto.CreateCustomerRequest;
import com.bank.customer.infrastructure.web.dto.CustomerCreditResponse;
import com.bank.customer.infrastructure.web.dto.CustomerResponse;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerControllerTest {

    @Mock private RegisterCustomerUseCase registerCustomer;
    @Mock private GetCustomerProfileUseCase getCustomerProfile;
    @Mock private GetCreditPositionUseCase getCreditPosition;
    @Mock private GetCreditStandingUseCase getCreditStanding;
    @Mock private GetScreeningProfileUseCase getScreeningProfile;
    @Mock private UpdateCreditLimitUseCase updateCreditLimit;
    @Mock private MoveCreditUseCase moveCredit;

    private CustomerController controller;

    @BeforeEach
    void setUp() {
        controller = new CustomerController(registerCustomer, getCustomerProfile, getCreditPosition, getCreditStanding,
            getScreeningProfile, updateCreditLimit, moveCredit);
    }

    /** The screening profile is the name and the KYC status, and nothing else: four fields, by type. */
    @Test
    void screeningProfileCarriesTheNameAndKycStatusOnly() throws Exception {
        Customer customer = Customer.createWithCreditScore(CustomerId.of("CUST-WEB-011"), "Ali", "Sample",
            "ali@example.com", "+971500000001", aed("1000.00"), 712);
        when(getScreeningProfile.getScreeningProfile(CustomerId.of("CUST-WEB-011"))).thenReturn(ScreeningProfile.of(customer));

        ScreeningProfileResponse body = controller.getScreeningProfile("CUST-WEB-011").getBody();

        assertThat(body).isEqualTo(new ScreeningProfileResponse("CUST-WEB-011", "Ali", "Sample", "PENDING"));
        assertThat(ScreeningProfileResponse.class.getRecordComponents())
            .extracting(java.lang.reflect.RecordComponent::getName)
            .containsExactly("customerId", "firstName", "lastName", "kycStatus");
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body))
            .doesNotContain("example.com", "+9715", "712", "credit", "income");
    }

    /** The eligibility decider reads the score with the position, and nothing personal; a missing score is absent, not null. */
    @Test
    void creditStandingCarriesThePositionAndTheScoreButNoPersonalData() throws Exception {
        Customer customer = Customer.createWithCreditScore(CustomerId.of("CUST-WEB-009"), "Ali", "Sample",
            "ali@example.com", "+971500000001", aed("1000.00"), 712);
        customer.reserveCredit(aed("1000.00"));
        when(getCreditStanding.getCreditStanding(CustomerId.of("CUST-WEB-009"))).thenReturn(CreditStanding.of(customer));
        var json = new com.fasterxml.jackson.databind.ObjectMapper();

        CreditStandingResponse body = controller.getCreditStanding("CUST-WEB-009").getBody();

        assertThat(body).isEqualTo(new CreditStandingResponse("CUST-WEB-009", "AED",
            customer.getCreditProfile().getCreditLimit().getAmount(), new BigDecimal("1000.00"),
            customer.getCreditProfile().getAvailableCredit().getAmount(), 712));
        assertThat(json.writeValueAsString(body))
            .contains("\"creditScore\":712").doesNotContain("Ali", "Sample", "example.com", "+9715");
        assertThat(json.writeValueAsString(CreditStandingResponse.from(new CreditStanding(profile("CUST-WEB-010").credit(), null))))
            .doesNotContain("creditScore");
    }

    @Test
    void createCustomerShouldReturnCreatedResponse() {
        CreateCustomerRequest request = new CreateCustomerRequest(
            "Ali", "Sample", "ali@example.com", "+971500000001", new BigDecimal("10000.00"), "AED");
        CustomerProfile profile = profile("CUST-WEB-001");
        when(registerCustomer.registerCustomer(any(RegisterCustomerCommand.class))).thenReturn(profile);

        ResponseEntity<CustomerResponse> entity = controller.createCustomer(request);

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(entity.getBody()).isEqualTo(CustomerResponse.from(profile));
        ArgumentCaptor<RegisterCustomerCommand> command = ArgumentCaptor.forClass(RegisterCustomerCommand.class);
        verify(registerCustomer).registerCustomer(command.capture());
        assertThat(command.getValue().initialCreditLimit()).isEqualTo(aed("10000.00"));
    }

    @Test
    void getCustomerShouldReturnOkResponse() {
        CustomerProfile profile = profile("CUST-WEB-002");
        when(getCustomerProfile.getCustomerProfile(CustomerId.of("CUST-WEB-002"))).thenReturn(profile);

        ResponseEntity<CustomerResponse> entity = controller.getCustomer("CUST-WEB-002");

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(entity.getBody().customerId()).isEqualTo("CUST-WEB-002");
        assertThat(entity.getBody().firstName()).isEqualTo("Ali");
    }

    @Test
    void updateCreditLimitShouldConvertMoneyAndDelegate() {
        CustomerProfile profile = profile("CUST-WEB-003");
        when(updateCreditLimit.updateCreditLimit(eq(CustomerId.of("CUST-WEB-003")), eq(aed("12000.00"))))
            .thenReturn(profile);

        ResponseEntity<CustomerResponse> entity = controller.updateCreditLimit("CUST-WEB-003",
            new CustomerController.UpdateCreditLimitRequest(new BigDecimal("12000.00"), "AED"));

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(entity.getBody()).isEqualTo(CustomerResponse.from(profile));
    }

    @Test
    void reserveCreditShouldBuildTheMovementCommand() {
        when(moveCredit.reserveCredit(any(CreditMovementCommand.class))).thenReturn(profile("CUST-WEB-004").credit());

        ResponseEntity<?> entity = controller.reserveCredit("key-4", "CUST-WEB-004",
            new CustomerController.ReserveCreditRequest(new BigDecimal("300.00"), "AED", "LOAN-4"));

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(entity.getBody()).isEqualTo(new CustomerCreditResponse(
            "CUST-WEB-004", "AED", new BigDecimal("10000.00"), new BigDecimal("1000.00"), new BigDecimal("9000.00")));
        verify(moveCredit).reserveCredit(
            new CreditMovementCommand(CustomerId.of("CUST-WEB-004"), aed("300.00"), "key-4", "LOAN-4"));
    }

    @Test
    void releaseCreditShouldBuildTheMovementCommand() {
        when(moveCredit.releaseCredit(any(CreditMovementCommand.class))).thenReturn(profile("CUST-WEB-005").credit());

        ResponseEntity<?> entity = controller.releaseCredit("key-5", "CUST-WEB-005",
            new CustomerController.ReleaseCreditRequest(new BigDecimal("150.00"), "AED", null));

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(entity.getBody()).isInstanceOf(CustomerCreditResponse.class);
        verify(moveCredit).releaseCredit(
            new CreditMovementCommand(CustomerId.of("CUST-WEB-005"), aed("150.00"), "key-5", null));
    }

    @Test
    void creditPositionCarriesNoPersonalData() {
        CustomerProfile profile = profile("CUST-WEB-007");
        when(getCreditPosition.getCreditPosition(CustomerId.of("CUST-WEB-007"))).thenReturn(profile.credit());

        CustomerCreditResponse body = controller.getCreditPosition("CUST-WEB-007").getBody();

        assertThat(body).isEqualTo(new CustomerCreditResponse(
            "CUST-WEB-007", "AED", new BigDecimal("10000.00"), new BigDecimal("1000.00"), new BigDecimal("9000.00")));
    }

    private static Money aed(String amount) {
        return Money.aed(new BigDecimal(amount));
    }

    private static CustomerProfile profile(String id) {
        Customer customer = Customer.create(CustomerId.of(id), "Ali", "Sample", "ali@example.com", "+971500000001",
            aed("10000.00"));
        customer.reserveCredit(aed("1000.00"));
        return CustomerProfile.of(customer);
    }
}
