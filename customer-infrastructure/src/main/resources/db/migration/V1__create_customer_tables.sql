-- svc-cus-profile-kyc owns these tables. Schema: sc_cus_profile_kyc (Flyway
-- runs with that schema as default, so names are unqualified). Split from the
-- monolith's V1__Create_customers_table.sql (public.customers). Differences
-- on purpose:
--   * customer_id is text: new customers get CUST-xxxxxxxx ids, migrated ones
--     keep the monolith id as text, which is what loans and payments already
--     reference (legacy_customer_id keeps the numeric id for reconciliation);
--   * email, phone, credit score and income exist for new customers only; the
--     monolith never stored them, so they are nullable;
--   * no trigger: updated_at is maintained by the Customer aggregate;
--   * the 1,000 to 1,000,000 creation range is an API rule, not a table rule,
--     because score-based limits are derived from income.
-- The parallel customer_management schema in the monolith was never used by
-- the running application and is not migrated.

CREATE TABLE customer (
    customer_id         VARCHAR(64)    PRIMARY KEY,
    first_name          VARCHAR(100)   NOT NULL,
    last_name           VARCHAR(100)   NOT NULL,
    email               VARCHAR(254),
    phone_number        VARCHAR(32),
    currency            VARCHAR(3)     NOT NULL,
    credit_limit        NUMERIC(19, 4) NOT NULL,
    used_credit         NUMERIC(19, 4) NOT NULL DEFAULT 0,
    credit_score        INTEGER,
    monthly_income      NUMERIC(19, 4),
    legacy_customer_id  BIGINT,
    legacy_synced_version BIGINT,
    created_at          TIMESTAMP      NOT NULL,
    updated_at          TIMESTAMP      NOT NULL,
    version             BIGINT         NOT NULL DEFAULT 0,

    CONSTRAINT ck_customer_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_customer_credit CHECK (credit_limit >= 0 AND used_credit >= 0 AND used_credit <= credit_limit),
    CONSTRAINT ck_customer_credit_score CHECK (credit_score IS NULL OR credit_score BETWEEN 300 AND 850),
    CONSTRAINT ck_customer_income CHECK (monthly_income IS NULL OR monthly_income > 0),
    CONSTRAINT uq_customer_legacy_id UNIQUE (legacy_customer_id)
);

-- One profile per e-mail address, compared case-insensitively.
CREATE UNIQUE INDEX uq_customer_email ON customer (lower(email)) WHERE email IS NOT NULL;

COMMENT ON TABLE customer IS 'Customer profile and credit position; personal data, encrypted at rest (KMS).';
COMMENT ON COLUMN customer.legacy_customer_id IS 'public.customers.id in the monolith, for migrated rows only.';
COMMENT ON COLUMN customer.legacy_synced_version IS 'Version the backfill last copied; equal to version while the service has not changed the row.';

-- Journal of applied credit reservations and releases. A caller retrying a
-- reserve or release with the same x-idempotency-key finds its movement here
-- and the credit is not moved twice. reference is what the credit was moved
-- for (the loan id when the loan service calls), so reservations can be
-- released and reconciled per loan.
CREATE TABLE credit_movement (
    movement_id      UUID           PRIMARY KEY,
    customer_id      VARCHAR(64)    NOT NULL REFERENCES customer (customer_id),
    idempotency_key  VARCHAR(128)   NOT NULL,
    movement_type    VARCHAR(16)    NOT NULL,
    currency         VARCHAR(3)     NOT NULL,
    amount           NUMERIC(19, 4) NOT NULL,
    reference        VARCHAR(128),
    occurred_at      TIMESTAMPTZ    NOT NULL,

    CONSTRAINT ck_credit_movement_type CHECK (movement_type IN ('RESERVE', 'RELEASE')),
    CONSTRAINT ck_credit_movement_amount CHECK (amount > 0),
    CONSTRAINT uq_credit_movement_key UNIQUE (customer_id, idempotency_key)
);

CREATE INDEX ix_credit_movement_occurred_at ON credit_movement (occurred_at);
CREATE INDEX ix_credit_movement_reference ON credit_movement (customer_id, reference) WHERE reference IS NOT NULL;
