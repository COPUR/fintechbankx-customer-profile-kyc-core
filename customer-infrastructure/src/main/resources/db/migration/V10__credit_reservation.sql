-- Release by reference (review of loan PR #14). One row per customer and
-- reference (the loan id when the loan service calls): what was reserved
-- under that reference and what has been released from it. A release naming
-- the reference takes at most reserved_amount - released_amount (else 422
-- RELEASE_EXCEEDS_RESERVATION). A release naming no reservation takes at
-- most the untracked used credit, customer.used_credit minus the sum of open
-- reservations (else 422 RESERVATION_NOT_FOUND), which covers balances
-- migrated from the monolith and reserves made without a reference.
--
-- No version column: every change to a row is written in the transaction
-- that also changes customer.used_credit, after the customer row's
-- optimistic version check, so races are decided on the customer row as for
-- reserve.
--
-- No backfill from credit_movement: nothing has been deployed. Movements
-- journalled before this migration (test and parity databases only) count
-- as untracked used credit.

CREATE TABLE credit_reservation (
    reservation_id   UUID           PRIMARY KEY,
    customer_id      VARCHAR(64)    NOT NULL REFERENCES customer (customer_id),
    reference        VARCHAR(128)   NOT NULL,
    currency         VARCHAR(3)     NOT NULL,
    reserved_amount  NUMERIC(19, 4) NOT NULL,
    released_amount  NUMERIC(19, 4) NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ    NOT NULL,
    updated_at       TIMESTAMPTZ    NOT NULL,

    CONSTRAINT ck_credit_reservation_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_credit_reservation_amounts CHECK (
        reserved_amount > 0 AND released_amount >= 0 AND released_amount <= reserved_amount),
    CONSTRAINT uq_credit_reservation_reference UNIQUE (customer_id, reference)
);

COMMENT ON TABLE credit_reservation IS 'Credit reserved per customer and reference (loan id); a release by reference takes at most reserved_amount - released_amount.';
COMMENT ON COLUMN credit_reservation.released_amount IS 'Sum of releases that named this reference; never above reserved_amount.';
