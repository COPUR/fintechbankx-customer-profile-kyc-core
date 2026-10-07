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
