package com.bank.customer.infrastructure.web;

import com.bank.customer.domain.CustomerNotFoundException;
import com.bank.customer.domain.IdempotencyKeyConflictException;
import com.bank.customer.domain.CustomerAlreadyExistsException;
import com.bank.customer.domain.InsufficientCreditException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

/**
 * Maps application and domain exceptions to the ErrorResponse shape of
 * customer-context.yaml. Credit figures stay in the logs; the client gets a
 * stable code and a generic message. Callers such as the loan service treat
 * 422 as "credit refused" and 5xx as "customer service unavailable".
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(CustomerNotFoundException.class)
    ResponseEntity<ErrorResponse> notFound(CustomerNotFoundException ex) {
        return error(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Customer not found");
    }

    @ExceptionHandler(InsufficientCreditException.class)
    ResponseEntity<ErrorResponse> insufficientCredit(InsufficientCreditException ex) {
        log.info("Credit reservation refused: {}", ex.getMessage());
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_CREDIT",
            "The customer does not have enough available credit");
    }

    @ExceptionHandler(IdempotencyKeyConflictException.class)
    ResponseEntity<ErrorResponse> idempotencyConflict(IdempotencyKeyConflictException ex) {
        return error(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", ex.getMessage());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ErrorResponse> concurrentUpdate(OptimisticLockingFailureException ex) {
        return error(HttpStatus.CONFLICT, "CONCURRENT_UPDATE", "The customer was changed by another request; retry");
    }

    /** Same code whether the check or the unique index caught it; never echoes the e-mail. */
    @ExceptionHandler(CustomerAlreadyExistsException.class)
    ResponseEntity<ErrorResponse> customerAlreadyExists(CustomerAlreadyExistsException ex) {
        return error(HttpStatus.CONFLICT, "CUSTOMER_ALREADY_EXISTS", ex.getMessage());
    }

    /** Two concurrent first calls with one idempotency key, or another unique key: the database lets one through. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorResponse> duplicate(DataIntegrityViolationException ex) {
        return error(HttpStatus.CONFLICT, "DUPLICATE_REQUEST", "A conflicting request was already processed; retry");
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class,
            MissingRequestHeaderException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ErrorResponse> badRequest(Exception ex) {
        String message = switch (ex) {
            case MethodArgumentNotValidException invalid -> invalid.getBindingResult().getAllErrors().stream()
                .map(e -> e.getDefaultMessage()).findFirst().orElse("Invalid request");
            case MissingRequestHeaderException missing -> missing.getHeaderName() + " header is required";
            case HttpMessageNotReadableException unreadable -> "Malformed request body";
            default -> ex.getMessage();
        };
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(code, message, MDC.get(CorrelationIdFilter.MDC_KEY), Instant.now()));
    }

    public record ErrorResponse(String code, String message, String interactionId, Instant timestamp) {
    }
}
