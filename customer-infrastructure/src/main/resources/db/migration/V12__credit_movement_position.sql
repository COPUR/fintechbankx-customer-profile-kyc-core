-- Review 5478760715 (customer #13): a replay of a credit movement (same
-- x-idempotency-key, same instruction) must answer with the position the
-- original call answered, not the position after later movements. Each
-- movement keeps the position it left behind: credit limit, used credit and
-- available credit, in the movement's currency (the customer's credit
-- currency, same as the amount's).
--
-- Rows from before this migration keep NULLs: a replay of such a movement
-- answers with the current position (the behaviour until now). Nothing is
-- backfilled, because the position after an old movement cannot be rebuilt
-- without replaying the whole journal.

ALTER TABLE credit_movement
    ADD COLUMN credit_limit_after     NUMERIC(19, 4),
    ADD COLUMN used_credit_after      NUMERIC(19, 4),
    ADD COLUMN available_credit_after NUMERIC(19, 4);

-- All three or none (a NULL in one of them must not slip through the second
-- branch as an unknown comparison); and the three describe one position.
ALTER TABLE credit_movement ADD CONSTRAINT ck_credit_movement_position CHECK (
    (credit_limit_after IS NULL AND used_credit_after IS NULL AND available_credit_after IS NULL)
    OR (credit_limit_after IS NOT NULL AND used_credit_after IS NOT NULL AND available_credit_after IS NOT NULL
        AND credit_limit_after >= 0 AND used_credit_after >= 0 AND used_credit_after <= credit_limit_after
        AND available_credit_after = credit_limit_after - used_credit_after));

COMMENT ON COLUMN credit_movement.credit_limit_after IS 'Credit limit after this movement, in currency; NULL for movements journalled before V12.';
COMMENT ON COLUMN credit_movement.used_credit_after IS 'Used credit after this movement; a replay of the movement answers with this position.';
COMMENT ON COLUMN credit_movement.available_credit_after IS 'credit_limit_after - used_credit_after.';
