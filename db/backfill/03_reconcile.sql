-- Step 3 of the customer data split, run by run-backfill.sh against the
-- CUSTOMER SERVICE database while backfill_stage still holds the snapshot.
-- Row 1 holds the staged totals, compared with the monolith totals taken in
-- the same snapshot as the export. Any further row is a problem, one per
-- customer: "<customer_id> <reason>".

\set ON_ERROR_STOP on

SELECT count(*)                                         AS customer_rows,
       coalesce(sum(credit_limit), 0)::numeric(19,2)      AS credit_limit_total,
       coalesce(sum(used_credit_limit), 0)::numeric(19,2) AS used_credit_total
  FROM backfill_stage.customers;

SELECT s.id::text || ' ' ||
       CASE
         WHEN t.customer_id IS NULL THEN 'missing in the service'
         WHEN t.version = t.legacy_synced_version
              AND (t.credit_limit, t.used_credit, t.first_name, t.last_name, t.version)
                  IS DISTINCT FROM (s.credit_limit, s.used_credit_limit, s.name, s.surname, s.version)
           THEN 'copy differs from the monolith'
         ELSE 'monolith changed it after the service did (two credit ledgers)'
       END
  FROM backfill_stage.customers s
  LEFT JOIN sc_cus_profile_kyc.customer t ON t.legacy_customer_id = s.id
 WHERE t.customer_id IS NULL
    OR (t.version = t.legacy_synced_version
        AND (t.credit_limit, t.used_credit, t.first_name, t.last_name, t.version)
            IS DISTINCT FROM (s.credit_limit, s.used_credit_limit, s.name, s.surname, s.version))
    OR (t.version <> t.legacy_synced_version AND s.version <> t.legacy_synced_version)
UNION ALL
SELECT customer_id || ' breaks an invariant (id or credit)'
  FROM sc_cus_profile_kyc.customer
 WHERE legacy_customer_id IS NOT NULL
   AND (customer_id <> legacy_customer_id::text OR used_credit > credit_limit);
