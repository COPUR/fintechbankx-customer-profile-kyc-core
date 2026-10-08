package com.bank.customer.infrastructure.persistence;

import com.bank.customer.domain.Customer;
import com.bank.customer.domain.port.out.CustomerRepository;
import com.bank.shared.kernel.domain.CustomerId;
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
        CustomerJpaEntity saved = customers.saveAndFlush(entity);
        customer.setVersion(saved.getVersion());
        return customer;
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
