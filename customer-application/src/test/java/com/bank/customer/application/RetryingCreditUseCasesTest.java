package com.bank.customer.application;

import com.bank.customer.domain.InsufficientCreditException;
import com.bank.customer.domain.port.in.CreditMovementCommand;
import com.bank.customer.domain.port.in.CreditPosition;
import com.bank.customer.domain.port.in.CustomerProfile;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A credit move that lost an optimistic race is run again in a new
 * transaction against the fresh row, so the caller gets the business answer
 * (200 or 422) instead of a 409 for a race the service can settle itself.
 */
class RetryingCreditUseCasesTest {

    private static final CustomerId CUSTOMER = CustomerId.of("CUST-RACE-1");
    private static final CreditMovementCommand RESERVE = new CreditMovementCommand(
        CUSTOMER, Money.aed(new BigDecimal("1500.00")), "LOAN-1:reserve", "LOAN-1");

    private final CustomerManagementService service = mock(CustomerManagementService.class);
    private final RetryingCreditUseCases retrying = new RetryingCreditUseCases(service, 3, Duration.ZERO);

    @Test
    void aLostRaceIsRetriedUntilItSucceeds() {
        CreditPosition position = mock(CreditPosition.class);
        when(service.reserveCredit(RESERVE))
            .thenThrow(new OptimisticLockingFailureException("v1"))
            .thenThrow(new OptimisticLockingFailureException("v2"))
            .thenReturn(position);

        assertThat(retrying.reserveCredit(RESERVE)).isSameAs(position);
        verify(service, times(3)).reserveCredit(RESERVE);
    }

    @Test
    void theAttemptCapEndsInTheConflict() {
        when(service.releaseCredit(RESERVE)).thenThrow(new OptimisticLockingFailureException("busy"));

        assertThatThrownBy(() -> retrying.releaseCredit(RESERVE)).isInstanceOf(OptimisticLockingFailureException.class);
        verify(service, times(3)).releaseCredit(RESERVE);
    }

    @Test
    void aBusinessRefusalIsNeverRetried() {
        when(service.reserveCredit(RESERVE)).thenThrow(new InsufficientCreditException("no"));

        assertThatThrownBy(() -> retrying.reserveCredit(RESERVE)).isInstanceOf(InsufficientCreditException.class);
        verify(service, times(1)).reserveCredit(RESERVE);
    }

    @Test
    void aLimitChangeIsRetriedToo() {
        Money limit = Money.aed(new BigDecimal("5000.00"));
        CustomerProfile profile = mock(CustomerProfile.class);
        when(service.updateCreditLimit(CUSTOMER, limit))
            .thenThrow(new OptimisticLockingFailureException("v1"))
            .thenReturn(profile);

        assertThat(retrying.updateCreditLimit(CUSTOMER, limit)).isSameAs(profile);
    }

    @Test
    void theCapIsAtLeastOneAttempt() {
        assertThatThrownBy(() -> new RetryingCreditUseCases(service, 0, Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
