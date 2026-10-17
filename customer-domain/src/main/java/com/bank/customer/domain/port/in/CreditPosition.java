package com.bank.customer.domain.port.in;

import com.bank.customer.domain.CreditProfile;
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
        return of(customer.getId(), customer.getCreditProfile());
    }

    /** The position a credit profile describes, for example the one a journalled movement left behind. */
    public static CreditPosition of(CustomerId customerId, CreditProfile credit) {
        return new CreditPosition(customerId, credit.getCreditLimit(), credit.getUsedCredit(), credit.getAvailableCredit());
    }
}
