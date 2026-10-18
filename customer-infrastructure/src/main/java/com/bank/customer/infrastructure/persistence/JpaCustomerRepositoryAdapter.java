package com.bank.customer.infrastructure.persistence;

import com.bank.customer.domain.Customer;
import com.bank.customer.domain.CustomerAlreadyExistsException;
import com.bank.customer.domain.IdentityLinkConflictException;
import com.bank.customer.domain.port.out.CustomerRepository;
import com.bank.shared.kernel.domain.CustomerId;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

/**
 * Out-port adapter for {@link CustomerRepository} over the service's own
 * schema (sc_cus_profile_kyc). Optimistic locking: a save whose aggregate
 * version differs from the stored row fails instead of overwriting a
 * concurrent change, so two loans cannot both take the last of a customer's
 * available credit.
 */
@Repository
@Transactional
public class JpaCustomerRepositoryAdapter implements CustomerRepository {

    /** V1__create_customer_tables.sql: unique on lower(email). */
    static final String EMAIL_UNIQUE_INDEX = "uq_customer_email";
    /** V4__customer_identity_link.sql: one customer per identity user. */
    static final String IDENTITY_USER_UNIQUE_INDEX = "uq_customer_identity_user";

    private final SpringDataCustomerRepository customers;

    public JpaCustomerRepositoryAdapter(SpringDataCustomerRepository customers) {
        this.customers = customers;
    }

    @Override
    public Customer save(Customer customer) {
        CustomerJpaEntity entity = customers.findById(customer.getId().getValue()).orElse(null);
        if (entity == null) {
            entity = CustomerPersistenceMapper.newEntity(customer);
        } else {
            if (!Objects.equals(entity.getVersion(), customer.getVersion())) {
                throw new OptimisticLockingFailureException(
                    "Customer " + customer.getId().getValue() + " is at version " + entity.getVersion()
                        + " but the change was made on version " + customer.getVersion());
            }
            CustomerPersistenceMapper.copyInto(customer, entity);
        }
        CustomerJpaEntity saved;
        try {
            saved = customers.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            if (violates(e, EMAIL_UNIQUE_INDEX)) {
                // Two registrations raced past existsByEmail: same answer as the check.
                throw new CustomerAlreadyExistsException(e);
            }
            if (violates(e, IDENTITY_USER_UNIQUE_INDEX)) {
                throw new IdentityLinkConflictException(e);
            }
            throw e;
        }
        customer.setVersion(saved.getVersion());
        return customer;
    }

    private static boolean violates(Throwable e, String constraint) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof org.hibernate.exception.ConstraintViolationException violation
                    && constraint.equalsIgnoreCase(violation.getConstraintName())) {
                return true;
            }
            if (t.getMessage() != null && t.getMessage().contains(constraint)) {
                return true;
            }
        }
        return false;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Customer> findById(CustomerId customerId) {
        return customers.findById(customerId.getValue()).map(CustomerPersistenceMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Customer> findByEmail(String email) {
        if (email == null) {
            return Optional.empty();
        }
        return customers.findByEmailIgnoreCase(email).map(CustomerPersistenceMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsById(CustomerId customerId) {
        return customers.existsById(customerId.getValue());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByEmail(String email) {
        return email != null && customers.existsByEmailIgnoreCase(email);
    }

    @Override
    public void deleteById(CustomerId customerId) {
        customers.deleteById(customerId.getValue());
    }
}
