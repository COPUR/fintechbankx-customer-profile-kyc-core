package com.bank.customer.domain.port.in;

import com.bank.customer.domain.Customer;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;

/**
 * A customer's credit position without personal data: what service callers
 * such as the loan service may see.
 */
public record CreditPosition(
    CustomerId customerId,
    Money creditLimit,
    Money usedCredit,
    Money availableCredit
) {

    public static CreditPosition of(Customer customer) {
        var credit = customer.getCreditProfile();
        return new CreditPosition(customer.getId(), credit.getCreditLimit(), credit.getUsedCredit(),
            credit.getAvailableCredit());
    }
}
