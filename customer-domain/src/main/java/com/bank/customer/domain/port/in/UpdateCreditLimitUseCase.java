package com.bank.customer.domain.port.in;

import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;

/** Changes a customer's credit limit (staff only). */
public interface UpdateCreditLimitUseCase {

    CustomerProfile updateCreditLimit(CustomerId customerId, Money newCreditLimit);
}
