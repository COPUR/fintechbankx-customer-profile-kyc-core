package com.bank.customer.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface SpringDataCustomerRepository extends JpaRepository<CustomerJpaEntity, String> {

    Optional<CustomerJpaEntity> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);
}
