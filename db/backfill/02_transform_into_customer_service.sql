-- Step 2 of the customer data split: copy the staged monolith rows into the
-- service's own table. Run by run-backfill.sh against the CUSTOMER SERVICE
-- database (db_cus_profile_kyc_<env>) after Flyway created sc_cus_profile_kyc.
-- Idempotent: customers already loaded are skipped, so a partial run can
-- simply be repeated, and a re-run never overwrites a change the service made.
--
-- Mapping decisions (see docs/migration/RUNBOOK-EXTRACT-cus-profile-kyc.md):
--   customer_id        = monolith customers.id as text; loans and payments already reference it
--   legacy_customer_id = monolith customers.id
--   first_name, last_name = name, surname
--   currency           = the run's currency parameter (the monolith stored none)
--   credit_limit, used_credit = credit_limit, used_credit_limit (the used amount already
--                        covers the loans the loan service migrates, so nothing is reserved again)
--   email, phone, credit score, income = NULL (never stored by the monolith)
--   created_at, updated_at, version kept

\set ON_ERROR_STOP on

BEGIN;

INSERT INTO sc_cus_profile_kyc.customer (
    customer_id, first_name, last_name, email, phone_number, currency, credit_limit, used_credit,
    credit_score, monthly_income, legacy_customer_id, created_at, updated_at, version)
SELECT c.id::text,
       c.name,
       c.surname,
       NULL,
       NULL,
       :'currency',
       c.credit_limit,
       c.used_credit_limit,
       NULL,
       NULL,
       c.id,
       c.created_at,
       c.updated_at,
       c.version
  FROM backfill_stage.customers c
ON CONFLICT (customer_id) DO NOTHING;

COMMIT;
