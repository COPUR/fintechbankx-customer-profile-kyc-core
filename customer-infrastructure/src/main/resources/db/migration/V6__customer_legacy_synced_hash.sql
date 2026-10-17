-- Backfill conflict detection by data, not by the monolith's version, which the
-- monolith never bumps (its CustomerEntity has no @Version; only an updated_at
-- trigger). legacy_synced_hash is the fingerprint of the monolith-owned fields
-- the backfill last copied: md5 of name, surname, credit_limit and
-- used_credit_limit (amounts at the monolith's scale 2), joined with '|'.
-- db/backfill computes the same expression; 03_reconcile.sql reports a customer
-- as changed on both sides when the service has changed it (version <>
-- legacy_synced_version) and the monolith row no longer matches the fingerprint.

ALTER TABLE customer ADD COLUMN legacy_synced_hash CHAR(32);

COMMENT ON COLUMN customer.legacy_synced_hash IS
    'md5 of the monolith name|surname|credit_limit|used_credit_limit the backfill last copied; NULL for customers created here.';

-- Rows copied before this column existed: while the service has not changed
-- them, their columns are still the monolith values of the last copy. Rows the
-- service has changed keep NULL; reconciliation reports them for a manual check.
UPDATE customer
   SET legacy_synced_hash = md5(concat_ws('|', first_name, last_name,
                                credit_limit::numeric(19, 2)::text, used_credit::numeric(19, 2)::text))
 WHERE legacy_customer_id IS NOT NULL
   AND version = legacy_synced_version;
