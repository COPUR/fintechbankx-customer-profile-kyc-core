package com.bank.customer.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

interface SpringDataCreditReservationRepository extends JpaRepository<CreditReservationJpaEntity, UUID> {

    Optional<CreditReservationJpaEntity> findByCustomerIdAndReference(String customerId, String reference);

    @Query("select coalesce(sum(r.reservedAmount - r.releasedAmount), 0) from CreditReservationJpaEntity r "
        + "where r.customerId = :customerId and r.currency = :currency")
    BigDecimal sumOpen(@Param("customerId") String customerId, @Param("currency") String currency);
}
