package com.bank.customer.infrastructure.web;

import com.bank.customer.application.CustomerManagementService;
import com.bank.customer.application.dto.CreateCustomerRequest;
import com.bank.customer.application.dto.CustomerResponse;
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
 * Credit reserve and release are called by other services (loan lifecycle)
 * with a SERVICE-role client-credentials token and an x-idempotency-key, so a
 * retried call never reserves twice.
 */
@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {
    
    private final CustomerManagementService customerService;
    
    public CustomerController(CustomerManagementService customerService) {
        this.customerService = customerService;
    }
    
    /**
     * FR-001: Create new customer
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('BANKER', 'ADMIN')")
    public ResponseEntity<CustomerResponse> createCustomer(@Valid @RequestBody CreateCustomerRequest request) {
        CustomerResponse response = customerService.createCustomer(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
    
    /**
     * FR-002: Get customer by ID
     */
    @GetMapping("/{customerId}")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'BANKER', 'ADMIN', 'SERVICE') and (#customerId == authentication.name or hasAnyRole('BANKER', 'ADMIN', 'SERVICE'))")
    public ResponseEntity<CustomerResponse> getCustomer(@PathVariable String customerId) {
        CustomerResponse response = customerService.findCustomerById(customerId);
        return ResponseEntity.ok(response);
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
        CustomerResponse response = customerService.updateCreditLimit(customerId, newLimit);
        return ResponseEntity.ok(response);
    }
    
    /**
     * FR-003: Reserve credit for customer
     */
    @PostMapping("/{customerId}/credit/reserve")
    @PreAuthorize("hasAnyRole('BANKER', 'ADMIN', 'SERVICE')")
    public ResponseEntity<CustomerResponse> reserveCredit(
            @RequestHeader(value = "x-idempotency-key", required = false) String idempotencyKey,
            @PathVariable String customerId,
            @RequestBody ReserveCreditRequest request) {
        
        Money amount = Money.of(request.amount(), Currency.getInstance(request.currency()));
        CustomerResponse response = customerService.reserveCredit(customerId, amount, idempotencyKey);
        return ResponseEntity.ok(response);
    }
    
    /**
     * FR-003: Release reserved credit
     */
    @PostMapping("/{customerId}/credit/release")
    @PreAuthorize("hasAnyRole('BANKER', 'ADMIN', 'SERVICE')")
    public ResponseEntity<CustomerResponse> releaseCredit(
            @RequestHeader(value = "x-idempotency-key", required = false) String idempotencyKey,
            @PathVariable String customerId,
            @RequestBody ReleaseCreditRequest request) {
        
        Money amount = Money.of(request.amount(), Currency.getInstance(request.currency()));
        CustomerResponse response = customerService.releaseCredit(customerId, amount, idempotencyKey);
        return ResponseEntity.ok(response);
    }
    
    // Request DTOs for credit operations
    public record UpdateCreditLimitRequest(java.math.BigDecimal amount, String currency) {}
    public record ReserveCreditRequest(java.math.BigDecimal amount, String currency) {}
    public record ReleaseCreditRequest(java.math.BigDecimal amount, String currency) {}
}