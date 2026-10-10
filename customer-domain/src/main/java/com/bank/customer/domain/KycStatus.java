package com.bank.customer.domain;

import com.bank.shared.kernel.domain.ValueObject;

import java.time.Instant;
import java.util.Objects;

/**
 * A customer's KYC status. MIGRATED: carried over from the monolith, which
 * onboarded the customer (decision pending with the user: migrated customers
 * start VERIFIED). STAFF: set by a banker or admin. verifiedAt is present
 * exactly when the status is VERIFIED; updatedBy is the staff subject that
 * last changed it (null for PENDING and MIGRATED).
 */
public record KycStatus(Status status, Source source, Instant verifiedAt, String updatedBy) implements ValueObject {

    public enum Status { PENDING, VERIFIED, REJECTED }

    public enum Source { MIGRATED, STAFF }

    public KycStatus {
        Objects.requireNonNull(status, "KYC status cannot be null");
        Objects.requireNonNull(source, "KYC source cannot be null");
        if (status == Status.VERIFIED && verifiedAt == null) {
            throw new IllegalArgumentException("A VERIFIED KYC status needs verifiedAt");
        }
        if (status != Status.VERIFIED && verifiedAt != null) {
            throw new IllegalArgumentException("Only a VERIFIED KYC status has verifiedAt");
        }
    }

    /** A customer registered in this service, waiting for staff to verify them. */
    public static KycStatus pending() {
        return new KycStatus(Status.PENDING, Source.STAFF, null, null);
    }

    /** A customer migrated from the monolith, verified as of the migration. */
    public static KycStatus migrated(Instant verifiedAt) {
        return new KycStatus(Status.VERIFIED, Source.MIGRATED, verifiedAt, null);
    }

    public boolean isVerified() {
        return status == Status.VERIFIED;
    }
}
