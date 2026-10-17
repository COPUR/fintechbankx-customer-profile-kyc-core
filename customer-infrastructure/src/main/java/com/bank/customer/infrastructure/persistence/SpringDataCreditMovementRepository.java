package com.bank.customer.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface SpringDataCreditMovementRepository extends JpaRepository<CreditMovementJpaEntity, UUID> {

    Optional<CreditMovementJpaEntity> findByCustomerIdAndIdempotencyKey(String customerId, String idempotencyKey);
}
