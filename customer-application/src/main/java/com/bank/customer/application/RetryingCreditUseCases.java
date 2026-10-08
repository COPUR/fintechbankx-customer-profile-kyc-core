package com.bank.customer.application;

import com.bank.customer.domain.port.in.CreditMovementCommand;
import com.bank.customer.domain.port.in.CreditPosition;
import com.bank.customer.domain.port.in.CustomerProfile;
import com.bank.customer.domain.port.in.MoveCreditUseCase;
import com.bank.customer.domain.port.in.UpdateCreditLimitUseCase;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * The credit use cases as inbound adapters see them. Each call runs
 * {@link CustomerManagementService} in its own transaction; when the save
 * loses an optimistic race (another reserve, release or limit change on the
 * same customer committed in between), the whole use case runs again on the
 * fresh row, so the aggregate re-checks the limit and the idempotency
 * journal against the committed state. A caller therefore gets the business
 * answer (the new position, or 422 when the credit is gone) rather than a 409.
 * After {@code customer.credit.max-attempts} lost races the conflict reaches
 * the caller as 409 CONCURRENT_UPDATE.
 *
 * used_credit <= credit_limit is held by the aggregate, the optimistic lock
 * and the database check ck_customer_credit; this class only settles races.
 */
@Service
@Primary
public class RetryingCreditUseCases implements MoveCreditUseCase, UpdateCreditLimitUseCase {

    private final CustomerManagementService service;
    private final int maxAttempts;
    private final Duration backoff;

    public RetryingCreditUseCases(CustomerManagementService service,
                                  @Value("${customer.credit.max-attempts:10}") int maxAttempts,
                                  @Value("${customer.credit.retry-backoff:PT0.01S}") Duration backoff) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("customer.credit.max-attempts must be at least 1");
        }
        this.service = service;
        this.maxAttempts = maxAttempts;
        this.backoff = backoff;
    }

    @Override
    public CreditPosition reserveCredit(CreditMovementCommand command) {
        return retrying(() -> service.reserveCredit(command));
    }

    @Override
    public CreditPosition releaseCredit(CreditMovementCommand command) {
        return retrying(() -> service.releaseCredit(command));
    }

    @Override
    public CustomerProfile updateCreditLimit(CustomerId customerId, Money newCreditLimit) {
        return retrying(() -> service.updateCreditLimit(customerId, newCreditLimit));
    }

    private <T> T retrying(Supplier<T> useCase) {
        for (int attempt = 1; ; attempt++) {
            try {
                return useCase.get();
            } catch (OptimisticLockingFailureException lostRace) {
                if (attempt >= maxAttempts) {
                    throw lostRace;
                }
                pause(attempt);
            }
        }
    }

    /** Jittered, growing pause so racing callers do not collide again in lockstep. */
    private void pause(int attempt) {
        long ceiling = backoff.toMillis() * attempt;
        if (ceiling <= 0) {
            return;
        }
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(ceiling / 2, ceiling + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying a credit move", e);
        }
    }
}
