package com.bank.customer.infrastructure.web;

import com.bank.customer.application.CustomerNotFoundException;
import com.bank.customer.application.IdempotencyKeyConflictException;
import com.bank.customer.domain.InsufficientCreditException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void creditRefusalIs422WithoutDisclosingCreditFigures() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "corr-422");

        ResponseEntity<ApiExceptionHandler.ErrorResponse> response = handler.insufficientCredit(
            new InsufficientCreditException("Customer 1 has insufficient credit. Requested: AED 10, Available: AED 5"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().code()).isEqualTo("INSUFFICIENT_CREDIT");
        assertThat(response.getBody().message()).doesNotContain("AED");
        assertThat(response.getBody().interactionId()).isEqualTo("corr-422");
        assertThat(response.getBody().timestamp()).isNotNull();
    }

    @Test
    void eachFailureHasAStableCodeAndStatus() {
        assertThat(handler.notFound(CustomerNotFoundException.withId("CUST-X")))
            .extracting(ResponseEntity::getStatusCode, r -> r.getBody().code())
            .containsExactly(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND");
        assertThat(handler.idempotencyConflict(IdempotencyKeyConflictException.forKey("k")))
            .extracting(ResponseEntity::getStatusCode, r -> r.getBody().code())
            .containsExactly(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED");
        assertThat(handler.concurrentUpdate(new OptimisticLockingFailureException("v")))
            .extracting(ResponseEntity::getStatusCode, r -> r.getBody().code())
            .containsExactly(HttpStatus.CONFLICT, "CONCURRENT_UPDATE");
        assertThat(handler.duplicate(new DataIntegrityViolationException("uq_customer_email")))
            .extracting(ResponseEntity::getStatusCode, r -> r.getBody().code())
            .containsExactly(HttpStatus.CONFLICT, "DUPLICATE_REQUEST");
        assertThat(handler.badRequest(new IllegalArgumentException("Email must be valid")))
            .extracting(ResponseEntity::getStatusCode, r -> r.getBody().message())
            .containsExactly(HttpStatus.BAD_REQUEST, "Email must be valid");
    }
}
