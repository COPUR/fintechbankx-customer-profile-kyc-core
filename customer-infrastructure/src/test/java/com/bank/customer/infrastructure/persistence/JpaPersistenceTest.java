package com.bank.customer.infrastructure.persistence;

import com.bank.customer.domain.CreditMovement;
import com.bank.customer.domain.Customer;
import com.bank.customer.domain.CustomerAlreadyExistsException;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The JPA adapters against the real schema: Flyway builds sc_cus_profile_kyc,
 * Hibernate validates the entities, and the optimistic lock and the
 * idempotency journal's unique key are exercised on PostgreSQL itself.
 */
@DataJpaTest(properties = {
    "spring.datasource.hikari.schema=sc_cus_profile_kyc",
    "spring.flyway.schemas=sc_cus_profile_kyc",
    "spring.flyway.default-schema=sc_cus_profile_kyc",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.properties.hibernate.default_schema=sc_cus_profile_kyc"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaCustomerRepositoryAdapter.class, JpaCreditMovementJournal.class, JpaCreditReservationLedger.class})
class JpaPersistenceTest {

    @BeforeAll
    static void requireDatabase() {
        PostgresTestDatabase.assumeAvailable();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresTestDatabase.register(registry);
    }

    @Autowired JpaCustomerRepositoryAdapter repository;
    @Autowired JpaCreditMovementJournal journal;
    @Autowired JpaCreditReservationLedger reservations;
    @Autowired JdbcTemplate jdbc;

    @Test
    void savesFindsAndUpdatesACustomer() {
        Customer customer = newCustomer("CUST-JPA-1", "jpa1@example.com");
        repository.save(customer);

        Customer loaded = repository.findById(CustomerId.of("CUST-JPA-1")).orElseThrow();
        loaded.reserveCredit(Money.aed(new BigDecimal("1500.00")));
        repository.save(loaded);

        assertThat(repository.findByEmail("JPA1@example.com")).get()
            .satisfies(found -> assertThat(found.getCreditProfile().getUsedCredit().getAmount()).isEqualByComparingTo("1500.00"));
        assertThat(repository.existsById(CustomerId.of("CUST-JPA-1"))).isTrue();
        assertThat(repository.existsByEmail("jpa1@example.com")).isTrue();
        assertThat(repository.existsByEmail(null)).isFalse();
        assertThat(repository.findByEmail(null)).isEmpty();
        assertThat(loaded.getVersion()).isEqualTo(1L);

        repository.deleteById(CustomerId.of("CUST-JPA-1"));
        assertThat(repository.existsById(CustomerId.of("CUST-JPA-1"))).isFalse();
    }

    /** Two registrations racing past the existsByEmail check: the unique index decides, with the same domain error. */
    @Test
    void aSecondCustomerWithTheSameEmailInAnyCaseIsADomainConflict() {
        repository.save(newCustomer("CUST-JPA-DUP-1", "race@example.com"));

        assertThatThrownBy(() -> repository.save(newCustomer("CUST-JPA-DUP-2", "RACE@example.com")))
            .isInstanceOf(CustomerAlreadyExistsException.class)
            .hasMessageNotContainingAny("race@example.com", "RACE@example.com");
    }

    @Test
    void theIdentityLinkIsStoredAndOneIdentityUserBelongsToOneCustomer() {
        Customer first = newCustomer("CUST-JPA-ID-1", "id1@example.com");
        first.linkIdentity(new com.bank.customer.domain.IdentityUserId("user-1"));
        repository.save(first);
        assertThat(repository.findById(CustomerId.of("CUST-JPA-ID-1")).orElseThrow().getIdentityUserId())
            .isEqualTo(new com.bank.customer.domain.IdentityUserId("user-1"));

        Customer second = newCustomer("CUST-JPA-ID-2", "id2@example.com");
        second.linkIdentity(new com.bank.customer.domain.IdentityUserId("user-1"));
        assertThatThrownBy(() -> repository.save(second))
            .isInstanceOf(com.bank.customer.domain.IdentityLinkConflictException.class);
    }

    @Test
    void aChangeMadeOnAStaleVersionIsRefused() {
        repository.save(newCustomer("CUST-JPA-2", "jpa2@example.com"));
        Customer first = repository.findById(CustomerId.of("CUST-JPA-2")).orElseThrow();
        Customer stale = repository.findById(CustomerId.of("CUST-JPA-2")).orElseThrow();

        first.reserveCredit(Money.aed(new BigDecimal("100.00")));
        repository.save(first);
        stale.reserveCredit(Money.aed(new BigDecimal("100.00")));

        assertThatThrownBy(() -> repository.save(stale)).isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    void theJournalFindsAMovementByKeyAndRefusesTheSameKeyTwice() {
        repository.save(newCustomer("CUST-JPA-3", "jpa3@example.com"));
        Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS);
        CreditMovement reserve = new CreditMovement(UUID.randomUUID(), CustomerId.of("CUST-JPA-3"), "LOAN-9:reserve",
            CreditMovement.Type.RESERVE, Money.aed(new BigDecimal("250.00")), "LOAN-9", at);

        journal.record(reserve);

        assertThat(journal.find(CustomerId.of("CUST-JPA-3"), "LOAN-9:reserve")).get()
            .satisfies(found -> {
                assertThat(found.reference()).isEqualTo("LOAN-9");
                assertThat(found.occurredAt()).isEqualTo(at);
                assertThat(found.sameInstruction(CreditMovement.Type.RESERVE, Money.aed(new BigDecimal("250")), "LOAN-9")).isTrue();
            });
        assertThat(journal.find(CustomerId.of("CUST-JPA-3"), "unknown")).isEmpty();
        assertThatThrownBy(() -> journal.record(new CreditMovement(UUID.randomUUID(), CustomerId.of("CUST-JPA-3"),
                "LOAN-9:reserve", CreditMovement.Type.RESERVE, Money.aed(new BigDecimal("250.00")), "LOAN-9", at)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** V10: one row per (customer, reference); the open amount sums what is still reserved. */
    @Test
    void reservationsAreStoredPerReferenceAndTheirOpenAmountIsSummed() {
        repository.save(newCustomer("CUST-JPA-RES", "jpares@example.com"));
        repository.save(newCustomer("CUST-JPA-RES-2", "jpares2@example.com"));
        CustomerId id = CustomerId.of("CUST-JPA-RES");
        java.util.Currency aed = java.util.Currency.getInstance("AED");
        assertThat(reservations.find(id, "LOAN-1")).isEmpty();
        assertThat(reservations.openAmount(id, aed)).isEqualTo(Money.aed(BigDecimal.ZERO));

        reservations.save(com.bank.customer.domain.CreditReservation.none(id, "LOAN-1", aed)
            .reserve(Money.aed(new BigDecimal("2500.00"))));
        reservations.save(com.bank.customer.domain.CreditReservation.none(id, "LOAN-2", aed)
            .reserve(Money.aed(new BigDecimal("1000.00"))));
        reservations.save(com.bank.customer.domain.CreditReservation.none(CustomerId.of("CUST-JPA-RES-2"), "LOAN-1", aed)
            .reserve(Money.aed(new BigDecimal("700.00"))));
        reservations.save(reservations.find(id, "LOAN-1").orElseThrow().release(Money.aed(new BigDecimal("1000.00"))));

        assertThat(reservations.find(id, "LOAN-1")).get().satisfies(found -> {
            assertThat(found.reserved()).isEqualTo(Money.aed(new BigDecimal("2500.00")));
            assertThat(found.released()).isEqualTo(Money.aed(new BigDecimal("1000.00")));
            assertThat(found.remaining()).isEqualTo(Money.aed(new BigDecimal("1500.00")));
        });
        assertThat(reservations.openAmount(id, aed)).isEqualTo(Money.aed(new BigDecimal("2500.00")));
        assertThat(jdbc.queryForObject("select count(*) from sc_cus_profile_kyc.credit_reservation where customer_id = 'CUST-JPA-RES'",
            Integer.class)).isEqualTo(2);
        // ck_credit_reservation_amounts: never more released than reserved.
        assertViolates("update sc_cus_profile_kyc.credit_reservation set released_amount = 2500.01 "
            + "where customer_id = 'CUST-JPA-RES' and reference = 'LOAN-1'");
        // One row per customer and reference.
        assertViolates("insert into sc_cus_profile_kyc.credit_reservation (reservation_id, customer_id, reference, currency, "
            + "reserved_amount, released_amount, created_at, updated_at) values (gen_random_uuid(), 'CUST-JPA-RES', 'LOAN-1', "
            + "'AED', 1, 0, now(), now())");
    }

    @Test
    void theKycStatusIsStoredAndTheDatabaseKeepsItConsistent() {
        repository.save(newCustomer("CUST-JPA-KYC", "jpakyc@example.com"));
        assertThat(repository.findById(CustomerId.of("CUST-JPA-KYC"))).get()
            .satisfies(found -> assertThat(found.getKycStatus()).isEqualTo(com.bank.customer.domain.KycStatus.pending()));

        Customer loaded = repository.findById(CustomerId.of("CUST-JPA-KYC")).orElseThrow();
        Instant at = Instant.parse("2026-10-08T10:00:00Z");
        loaded.verifyKyc("banker-sub-1", at);
        repository.save(loaded);

        assertThat(repository.findById(CustomerId.of("CUST-JPA-KYC"))).get()
            .satisfies(found -> assertThat(found.getKycStatus()).isEqualTo(new com.bank.customer.domain.KycStatus(
                com.bank.customer.domain.KycStatus.Status.VERIFIED, com.bank.customer.domain.KycStatus.Source.STAFF,
                at, "banker-sub-1")));
        assertThat(jdbc.queryForObject("select kyc_updated_by from sc_cus_profile_kyc.customer where customer_id = 'CUST-JPA-KYC'",
            String.class)).isEqualTo("banker-sub-1");
        // ck_customer_kyc: verified_at present exactly when VERIFIED; status and source from the enums.
        assertViolates("update sc_cus_profile_kyc.customer set kyc_verified_at = null where customer_id = 'CUST-JPA-KYC'");
        assertViolates("update sc_cus_profile_kyc.customer set kyc_status = 'APPROVED' where customer_id = 'CUST-JPA-KYC'");
        assertViolates("update sc_cus_profile_kyc.customer set kyc_source = 'API' where customer_id = 'CUST-JPA-KYC'");
    }

    /** Each violation runs behind a savepoint so the test transaction stays usable for the next one. */
    private void assertViolates(String sql) {
        jdbc.execute("savepoint kyc_check");
        try {
            assertThatThrownBy(() -> jdbc.update(sql)).isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.execute("rollback to savepoint kyc_check");
        }
    }

    private static Customer newCustomer(String id, String email) {
        Customer customer = Customer.create(CustomerId.of(id), "Test", "Customer", email, "+971500000000",
            Money.aed(new BigDecimal("10000.00")));
        customer.clearDomainEvents();
        return customer;
    }
}
