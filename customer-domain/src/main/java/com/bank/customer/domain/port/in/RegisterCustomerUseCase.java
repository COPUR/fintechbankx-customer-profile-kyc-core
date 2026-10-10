package com.bank.customer.domain.port.in;

/** Registers a new customer (staff only). */
public interface RegisterCustomerUseCase {

    CustomerProfile registerCustomer(RegisterCustomerCommand command);
}
