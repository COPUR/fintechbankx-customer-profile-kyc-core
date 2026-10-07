-- Step 3 of the customer data split, run by run-backfill.sh against the
-- CUSTOMER SERVICE database. Row 1 holds totals compared with the monolith;
-- any further row is a migrated customer breaking an invariant.

\set ON_ERROR_STOP on

SELECT count(*)                                   AS customer_rows,
       coalesce(sum(credit_limit), 0)::numeric(19,2) AS credit_limit_total,
       coalesce(sum(used_credit), 0)::numeric(19,2)  AS used_credit_total
  FROM sc_cus_profile_kyc.customer
 WHERE legacy_customer_id IS NOT NULL;

-- Per-customer invariants that must hold for every migrated customer (expect 0 rows).
SELECT customer_id, legacy_customer_id
  FROM sc_cus_profile_kyc.customer
 WHERE legacy_customer_id IS NOT NULL
   AND (customer_id <> legacy_customer_id::text OR used_credit > credit_limit OR email IS NOT NULL);
