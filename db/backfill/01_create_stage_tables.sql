-- Step 1 of the customer data split: a staging table in the CUSTOMER SERVICE
-- database that receives a CSV copy of the monolith's public.customers
-- (enterprise-loan-management-system V1__Create_customers_table.sql).

\set ON_ERROR_STOP on

DROP SCHEMA IF EXISTS backfill_stage CASCADE;
CREATE SCHEMA backfill_stage;

CREATE TABLE backfill_stage.customers (
    id                BIGINT         PRIMARY KEY,
    name              VARCHAR(100)   NOT NULL,
    surname           VARCHAR(100)   NOT NULL,
    credit_limit      NUMERIC(19, 2) NOT NULL,
    used_credit_limit NUMERIC(19, 2) NOT NULL,
    created_at        TIMESTAMP      NOT NULL,
    updated_at        TIMESTAMP      NOT NULL,
    version           BIGINT         NOT NULL
);

-- Fingerprint of the monolith-owned fields the backfill copies. The same
-- expression fills customer.legacy_synced_hash (V6), so a monolith change is
-- detected from the data even though the monolith never bumps its version.
CREATE FUNCTION backfill_stage.synced_hash(name TEXT, surname TEXT, credit_limit NUMERIC, used_credit NUMERIC)
RETURNS CHAR(32) LANGUAGE sql IMMUTABLE STRICT AS $$
    SELECT md5(concat_ws('|', name, surname, credit_limit::numeric(19, 2)::text, used_credit::numeric(19, 2)::text))
$$;
