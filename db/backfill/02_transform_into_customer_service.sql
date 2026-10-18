-- Step 2 of the customer data split: copy the staged monolith rows into the
-- service's own table. Run by run-backfill.sh against the CUSTOMER SERVICE
-- database (db_cus_profile_kyc_<env>) after Flyway created sc_cus_profile_kyc.
-- Re-runnable until cut-over: the monolith keeps changing customers until the
-- write freeze (customer cut-over step 2), so a re-run refreshes every
-- migrated customer the service has not changed yet (version =
-- legacy_synced_version; the service's own JPA version). A row the service
-- has changed is never overwritten; 03_reconcile.sql reports it if the
-- monolith row no longer matches legacy_synced_hash, the fingerprint of the
-- last copy. The monolith's version is not used for that: the monolith never
-- bumps it.
--
-- Mapping decisions (see docs/migration/RUNBOOK-EXTRACT-cus-profile-kyc.md):
--   customer_id        = monolith customers.id as text; loans and payments already reference it
--   legacy_customer_id = monolith customers.id
--   first_name, last_name = name, surname
--   currency           = the run's currency parameter (the monolith stored none)
--   credit_limit       = credit_limit
--   used_credit        = 0 on the first copy. customers.used_credit_limit is NEVER copied: the live
--                        monolith moves credit through an in-memory map and never writes that column,
--                        so it holds the seed value, not a credit position. Runbook step 3a is the
--                        only source of used_credit: it rebuilds one credit_reservation row per open
--                        monolith loan and sets used_credit to the customer's open sum. A re-run
--                        never touches used_credit (not in the ON CONFLICT update), so the value
--                        step 3a set survives later runs.
--   email, phone, credit score, income = NULL (never stored by the monolith)
--   created_at, updated_at, version kept; legacy_synced_version = version
--   legacy_synced_hash = backfill_stage.synced_hash(name, surname, credit_limit); used credit is not in it
--   kyc_status, kyc_source, kyc_verified_at = VERIFIED, MIGRATED, time of the first copy (V7; decision
--                        pending with the user). A re-run never changes the KYC columns.

\set ON_ERROR_STOP on

BEGIN;

INSERT INTO sc_cus_profile_kyc.customer (
    customer_id, first_name, last_name, email, phone_number, currency, credit_limit, used_credit,
    credit_score, monthly_income, legacy_customer_id, created_at, updated_at, version, legacy_synced_version,
    legacy_synced_hash, kyc_status, kyc_source, kyc_verified_at)
SELECT c.id::text,
       c.name,
       c.surname,
       NULL,
       NULL,
       :'currency',
       c.credit_limit,
       0,
       NULL,
       NULL,
       c.id,
       c.created_at,
       c.updated_at,
       c.version,
       c.version,
       backfill_stage.synced_hash(c.name, c.surname, c.credit_limit),
       'VERIFIED',
       'MIGRATED',
       now()
  FROM backfill_stage.customers c
ON CONFLICT (customer_id) DO UPDATE SET
       first_name            = EXCLUDED.first_name,
       last_name             = EXCLUDED.last_name,
       credit_limit          = EXCLUDED.credit_limit,
       updated_at            = EXCLUDED.updated_at,
       version               = EXCLUDED.version,
       legacy_synced_version = EXCLUDED.version,
       legacy_synced_hash    = EXCLUDED.legacy_synced_hash
 WHERE customer.version = customer.legacy_synced_version;

COMMIT;
