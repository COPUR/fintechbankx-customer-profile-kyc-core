-- KYC status of a customer, read by payments (GET .../kyc-status) and set by
-- staff (PUT .../kyc-status). New capability: the monolith stored no KYC state
-- for these customers. Decision pending with the user: migrated monolith
-- customers start VERIFIED/MIGRATED (the monolith onboarded them).
--   kyc_status       PENDING | VERIFIED | REJECTED
--   kyc_source       MIGRATED (carried over from the monolith) | STAFF (set here)
--   kyc_verified_at  present exactly when VERIFIED
--   kyc_updated_by   token subject of the staff member who last changed it
-- No column defaults: the service and the backfill always write them.

ALTER TABLE customer
    ADD COLUMN kyc_status      VARCHAR(16),
    ADD COLUMN kyc_source      VARCHAR(16),
    ADD COLUMN kyc_verified_at TIMESTAMPTZ,
    ADD COLUMN kyc_updated_by  VARCHAR(128);

-- Rows that exist now: migrated monolith customers are verified as of this
-- migration; customers created in this service were never checked, so they
-- stay PENDING until staff verify them.
UPDATE customer
   SET kyc_status = 'VERIFIED', kyc_source = 'MIGRATED', kyc_verified_at = now()
 WHERE legacy_customer_id IS NOT NULL;
UPDATE customer
   SET kyc_status = 'PENDING', kyc_source = 'STAFF'
 WHERE legacy_customer_id IS NULL;

ALTER TABLE customer
    ALTER COLUMN kyc_status SET NOT NULL,
    ALTER COLUMN kyc_source SET NOT NULL,
    ADD CONSTRAINT ck_customer_kyc_status CHECK (kyc_status IN ('PENDING', 'VERIFIED', 'REJECTED')),
    ADD CONSTRAINT ck_customer_kyc_source CHECK (kyc_source IN ('MIGRATED', 'STAFF')),
    ADD CONSTRAINT ck_customer_kyc_verified_at CHECK ((kyc_status = 'VERIFIED') = (kyc_verified_at IS NOT NULL));

COMMENT ON COLUMN customer.kyc_status IS 'PENDING, VERIFIED or REJECTED.';
COMMENT ON COLUMN customer.kyc_source IS 'MIGRATED from the monolith, or STAFF in this service.';
COMMENT ON COLUMN customer.kyc_verified_at IS 'When the customer was verified; present exactly when kyc_status is VERIFIED.';
COMMENT ON COLUMN customer.kyc_updated_by IS 'Token subject of the staff member who last changed the KYC status.';
