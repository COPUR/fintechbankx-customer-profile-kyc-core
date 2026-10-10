package com.bank.customer.domain;

import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * KYC status on the customer: PENDING, VERIFIED or REJECTED, set by staff or
 * carried over from the monolith (MIGRATED). verifiedAt is present exactly
 * when the status is VERIFIED.
 */
@Tag("unit")
class KycStatusTest {

    private static final Instant AT = Instant.parse("2026-10-08T10:00:00Z");

    @Test
    void aNewCustomerStartsPendingFromStaff() {
        Customer customer = newCustomer();

        assertThat(customer.getKycStatus()).isEqualTo(KycStatus.pending());
        assertThat(customer.getKycStatus().status()).isEqualTo(KycStatus.Status.PENDING);
        assertThat(customer.getKycStatus().source()).isEqualTo(KycStatus.Source.STAFF);
        assertThat(customer.getKycStatus().verifiedAt()).isNull();
        assertThat(customer.getKycStatus().updatedBy()).isNull();
        assertThat(customer.getKycStatus().isVerified()).isFalse();
    }

    @Test
    void aMigratedCustomerIsVerifiedFromTheMonolith() {
        KycStatus migrated = KycStatus.migrated(AT);

        assertThat(migrated.status()).isEqualTo(KycStatus.Status.VERIFIED);
        assertThat(migrated.source()).isEqualTo(KycStatus.Source.MIGRATED);
        assertThat(migrated.verifiedAt()).isEqualTo(AT);
        assertThat(migrated.isVerified()).isTrue();
    }

    @Test
    void verifiedAtIsPresentExactlyWhenVerified() {
        assertThatThrownBy(() -> new KycStatus(KycStatus.Status.VERIFIED, KycStatus.Source.STAFF, null, "banker-1"))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("A VERIFIED KYC status needs verifiedAt");
        assertThatThrownBy(() -> new KycStatus(KycStatus.Status.REJECTED, KycStatus.Source.STAFF, AT, "banker-1"))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Only a VERIFIED KYC status has verifiedAt");
        assertThatThrownBy(() -> new KycStatus(null, KycStatus.Source.STAFF, null, null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new KycStatus(KycStatus.Status.PENDING, null, null, null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void staffVerifyRecordsWhoAndWhenAndRaisesTheChange() {
        Customer customer = newCustomer();

        assertThat(customer.verifyKyc("banker-1", AT)).isTrue();

        assertThat(customer.getKycStatus()).isEqualTo(
            new KycStatus(KycStatus.Status.VERIFIED, KycStatus.Source.STAFF, AT, "banker-1"));
        assertThat(customer.getDomainEvents()).singleElement().isInstanceOfSatisfying(CustomerKycStatusChangedEvent.class, e -> {
            assertThat(e.getCustomerId()).isEqualTo(customer.getId());
            assertThat(e.getPreviousStatus()).isEqualTo(KycStatus.Status.PENDING);
            assertThat(e.getKycStatus()).isEqualTo(customer.getKycStatus());
        });
    }

    @Test
    void staffRejectClearsVerifiedAtAndRaisesTheChange() {
        Customer customer = newCustomer();
        customer.verifyKyc("banker-1", AT);
        customer.clearDomainEvents();

        assertThat(customer.rejectKyc("admin-1", AT.plusSeconds(60))).isTrue();

        assertThat(customer.getKycStatus()).isEqualTo(
            new KycStatus(KycStatus.Status.REJECTED, KycStatus.Source.STAFF, null, "admin-1"));
        assertThat(customer.getDomainEvents()).singleElement().isInstanceOfSatisfying(CustomerKycStatusChangedEvent.class,
            e -> assertThat(e.getPreviousStatus()).isEqualTo(KycStatus.Status.VERIFIED));
    }

    @Test
    void aRejectedCustomerCanBeVerifiedAfterReview() {
        Customer customer = newCustomer();
        customer.rejectKyc("banker-1", AT);

        assertThat(customer.verifyKyc("banker-2", AT.plusSeconds(3600))).isTrue();
        assertThat(customer.getKycStatus().verifiedAt()).isEqualTo(AT.plusSeconds(3600));
        assertThat(customer.getKycStatus().updatedBy()).isEqualTo("banker-2");
    }

    @Test
    void settingTheSameStatusAgainChangesNothingAndRaisesNoEvent() {
        Customer customer = newCustomer();
        customer.verifyKyc("banker-1", AT);
        customer.clearDomainEvents();

        assertThat(customer.verifyKyc("banker-2", AT.plusSeconds(60))).isFalse();

        assertThat(customer.getKycStatus().verifiedAt()).as("first verification kept").isEqualTo(AT);
        assertThat(customer.getKycStatus().updatedBy()).isEqualTo("banker-1");
        assertThat(customer.getDomainEvents()).isEmpty();
    }

    @Test
    void staffVerificationReplacesAMigratedStatusOnlyWhenItChangesIt() {
        Customer migrated = Customer.rehydrate(snapshot(KycStatus.migrated(AT)));

        assertThat(migrated.verifyKyc("banker-1", AT.plusSeconds(60))).isFalse();
        assertThat(migrated.getKycStatus().source()).isEqualTo(KycStatus.Source.MIGRATED);
        assertThat(migrated.rejectKyc("banker-1", AT.plusSeconds(60))).isTrue();
        assertThat(migrated.getKycStatus().source()).isEqualTo(KycStatus.Source.STAFF);
    }

    @Test
    void whoChangedItAndWhenAreRequired() {
        Customer customer = newCustomer();

        for (String by : new String[] {null, "", "  "}) {
            assertThatThrownBy(() -> customer.verifyKyc(by, AT)).as(String.valueOf(by))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Who changed the KYC status is required");
            assertThatThrownBy(() -> customer.rejectKyc(by, AT)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> customer.verifyKyc("banker-1", null)).isInstanceOf(NullPointerException.class);
        assertThat(customer.getKycStatus()).isEqualTo(KycStatus.pending());
        assertThat(customer.getDomainEvents()).isEmpty();
    }

    @Test
    void rehydrateKeepsThePersistedStatusAndOldSnapshotsArePending() {
        KycStatus rejected = new KycStatus(KycStatus.Status.REJECTED, KycStatus.Source.STAFF, null, "banker-1");

        assertThat(Customer.rehydrate(snapshot(rejected)).getKycStatus()).isEqualTo(rejected);
        assertThat(Customer.rehydrate(new CustomerSnapshot(CustomerId.of("CUST-KYC-2"), "A", "B", null, null,
                Money.aed(new BigDecimal("100.00")), Money.aed(BigDecimal.ZERO), null, null, null, null, 0L))
            .getKycStatus()).isEqualTo(KycStatus.pending());
    }

    private static Customer newCustomer() {
        Customer customer = Customer.create(CustomerId.of("CUST-KYC-1"), "Ali", "Sample", "ali@example.com", null,
            Money.aed(new BigDecimal("5000.00")));
        customer.clearDomainEvents();
        return customer;
    }

    private static CustomerSnapshot snapshot(KycStatus kyc) {
        return new CustomerSnapshot(CustomerId.of("1"), "Amina", "Haddad", null, null,
            Money.usd(new BigDecimal("50000.00")), Money.usd(new BigDecimal("20000.00")), null, null, null, null, 3L,
            null, kyc);
    }
}
