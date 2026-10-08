-- Test data for regression parity runs (enterprise-loan-management-system PR #103).
-- TEST AND PARITY ENVIRONMENTS ONLY. Not a Flyway migration: never put it in
-- db/migration and never run it against a production database.
--
-- The three customers the monolith's CustomerCreditServiceAdapter hard-codes
-- (loan-context/.../external/CustomerCreditServiceAdapter.java), in USD as
-- that adapter reports them. Names are synthetic; e-mail, phone, score and
-- income stay empty. They are not monolith rows, so they carry no
-- legacy_customer_id and are not part of the db/backfill reconciliation.
--
-- Re-runnable: a reload resets the three credit positions, bumps the version
-- (so an aggregate loaded before the reset cannot overwrite it) and deletes
-- their credit_movement rows, so a parity run can reuse its idempotency keys.
-- Run after Flyway has created sc_cus_profile_kyc (the service has started once):
--   psql "<customer service conninfo>" -X -1 -v ON_ERROR_STOP=1 -f db/fixtures/parity_seed_customers.sql

DELETE FROM sc_cus_profile_kyc.credit_movement
 WHERE customer_id IN ('CUST-12345678', 'CUST-87654321', 'CUST-11111111');

INSERT INTO sc_cus_profile_kyc.customer AS c (
    customer_id, first_name, last_name, email, phone_number, currency, credit_limit, used_credit,
    credit_score, monthly_income, legacy_customer_id, legacy_synced_version, created_at, updated_at, version)
VALUES
    ('CUST-12345678', 'Parity', 'Seed One',   NULL, NULL, 'USD', 100000.00,     0.00, NULL, NULL, NULL, NULL,
     TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-01 00:00:00', 0),
    ('CUST-87654321', 'Parity', 'Seed Two',   NULL, NULL, 'USD',  50000.00, 10000.00, NULL, NULL, NULL, NULL,
     TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-01 00:00:00', 0),
    ('CUST-11111111', 'Parity', 'Seed Three', NULL, NULL, 'USD',  25000.00, 20000.00, NULL, NULL, NULL, NULL,
     TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-01 00:00:00', 0)
ON CONFLICT (customer_id) DO UPDATE SET
    first_name   = EXCLUDED.first_name,
    last_name    = EXCLUDED.last_name,
    currency     = EXCLUDED.currency,
    credit_limit = EXCLUDED.credit_limit,
    used_credit  = EXCLUDED.used_credit,
    updated_at   = EXCLUDED.updated_at,
    version      = c.version + 1;
