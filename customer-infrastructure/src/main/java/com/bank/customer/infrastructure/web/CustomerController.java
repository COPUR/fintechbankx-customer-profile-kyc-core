package com.bank.customer.infrastructure.web;

import com.bank.customer.domain.port.in.CreditMovementCommand;
import com.bank.customer.domain.port.in.GetCreditPositionUseCase;
import com.bank.customer.domain.port.in.GetCustomerProfileUseCase;
import com.bank.customer.domain.port.in.MoveCreditUseCase;
import com.bank.customer.domain.port.in.RegisterCustomerUseCase;
import com.bank.customer.domain.port.in.UpdateCreditLimitUseCase;
import com.bank.customer.infrastructure.web.dto.CreateCustomerRequest;
import com.bank.customer.infrastructure.web.dto.CustomerCreditResponse;
import com.bank.customer.infrastructure.web.dto.CustomerResponse;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Currency;

/**
 * REST Controller for Customer Management
 * 
 * Implements Hexagonal Architecture - Adapter for HTTP requests
 * Functional Requirements: FR-001 through FR-004
 *
 * Credit reserve, release and the credit-position read are called by other
 * services (loan lifecycle) with a SERVICE-role client-credentials token whose
 * client is on the allowed list (ServiceCallerPolicy). Services never get the
 * full customer record: all three answer with the credit position only, and
 * personal data stays with staff and the customer.
 * Every credit movement needs an x-idempotency-key, so a retried call never
 * moves credit twice.
 */
@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {
    
    static final String IDEMPOTENCY_KEY = "x-idempotency-key";
    static final String CREDIT_CALLERS =
        "hasAnyRole('BANKER', 'ADMIN') or (hasRole('SERVICE') and @serviceCallers.allowed(authentication))";

    private final RegisterCustomerUseCase registerCustomer;
    private final GetCustomerProfileUseCase getCustomerProfile;
    private final GetCreditPositionUseCase getCreditPosition;
    private final UpdateCreditLimitUseCase updateCreditLimit;
    private final MoveCreditUseCase moveCredit;

    public CustomerController(RegisterCustomerUseCase registerCustomer,
                              GetCustomerProfileUseCase getCustomerProfile,
                              GetCreditPositionUseCase getCreditPosition,
                              UpdateCreditLimitUseCase updateCreditLimit,
                              MoveCreditUseCase moveCredit) {
        this.registerCustomer = registerCustomer;
        this.getCustomerProfile = getCustomerProfile;
        this.getCreditPosition = getCreditPosition;
        this.updateCreditLimit = updateCreditLimit;
        this.moveCredit = moveCredit;
    }
    
    /**
     * FR-001: Create new customer
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('BANKER', 'ADMIN')")
    public ResponseEntity<CustomerResponse> createCustomer(@Valid @RequestBody CreateCustomerRequest request) {
        CustomerResponse response = CustomerResponse.from(registerCustomer.registerCustomer(request.toCommand()));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
    
    /**
     * FR-002: Get customer by ID
     */
    @GetMapping("/{customerId}")
    @PreAuthorize("hasAnyRole('BANKER', 'ADMIN') or (hasRole('CUSTOMER') and #customerId == authentication.name)")
    public ResponseEntity<CustomerResponse> getCustomer(@PathVariable String customerId) {
        CustomerResponse response = CustomerResponse.from(getCustomerProfile.getCustomerProfile(CustomerId.of(customerId)));
        return ResponseEntity.ok(response);
    }

    /**
     * Credit position only (limit, used, available), without personal data.
     */
    @GetMapping("/{customerId}/credit")
    @PreAuthorize(CREDIT_CALLERS + " or (hasRole('CUSTOMER') and #customerId == authentication.name)")
    public ResponseEntity<CustomerCreditResponse> getCreditPosition(@PathVariable String customerId) {
        return ResponseEntity.ok(CustomerCreditResponse.from(getCreditPosition.getCreditPosition(CustomerId.of(customerId))));
    }
    
    /**
     * FR-003: Update customer credit limit
     */
    @PutMapping("/{customerId}/credit-limit")
    @PreAuthorize("hasAnyRole('BANKER', 'ADMIN')")
    public ResponseEntity<CustomerResponse> updateCreditLimit(
            @PathVariable String customerId, 
            @RequestBody UpdateCreditLimitRequest request) {
        
        Money newLimit = Money.of(request.amount(), Currency.getInstance(request.currency()));
        CustomerResponse response = CustomerResponse.from(updateCreditLimit.updateCreditLimit(CustomerId.of(customerId), newLimit));
        return ResponseEntity.ok(response);
    }
    
    /**
     * FR-003: Reserve credit for customer
     */
    @PostMapping("/{customerId}/credit/reserve")
    @PreAuthorize(CREDIT_CALLERS)
    public ResponseEntity<CustomerCreditResponse> reserveCredit(
            @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
            @PathVariable String customerId,
            @RequestBody ReserveCreditRequest request) {
        
        Money amount = Money.of(request.amount(), Currency.getInstance(request.currency()));
        CustomerCreditResponse response = CustomerCreditResponse.from(moveCredit.reserveCredit(
            new CreditMovementCommand(CustomerId.of(customerId), amount, idempotencyKey, request.reference())));
        return ResponseEntity.ok(response);
    }
    
    /**
     * FR-003: Release reserved credit
     */
    @PostMapping("/{customerId}/credit/release")
    @PreAuthorize(CREDIT_CALLERS)
    public ResponseEntity<CustomerCreditResponse> releaseCredit(
            @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
            @PathVariable String customerId,
            @RequestBody ReleaseCreditRequest request) {
        
        Money amount = Money.of(request.amount(), Currency.getInstance(request.currency()));
        CustomerCreditResponse response = CustomerCreditResponse.from(moveCredit.releaseCredit(
            new CreditMovementCommand(CustomerId.of(customerId), amount, idempotencyKey, request.reference())));
        return ResponseEntity.ok(response);
    }
    
    // Request DTOs for credit operations
    public record UpdateCreditLimitRequest(java.math.BigDecimal amount, String currency) {}
    /** reference: what the credit is reserved for, for example the loan id. */
    public record ReserveCreditRequest(java.math.BigDecimal amount, String currency, String reference) {}
    public record ReleaseCreditRequest(java.math.BigDecimal amount, String currency, String reference) {}
}