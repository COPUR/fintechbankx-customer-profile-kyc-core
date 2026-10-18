-- Platform ruling (parked metrics): outbox.parked.events is a counter
-- incremented once per parked row. The relay marks a row it parks as counted
-- in the same update; a row parked by an operator (runbook UPDATE) keeps
-- park_counted false until the relay counts it once, as OperatorPark.
ALTER TABLE outbox_event ADD COLUMN park_counted BOOLEAN NOT NULL DEFAULT FALSE;

-- Rows parked before this migration are not counted again.
UPDATE outbox_event SET park_counted = TRUE WHERE parked_at IS NOT NULL;

COMMENT ON COLUMN outbox_event.park_counted IS 'TRUE once outbox_parked_events_total has counted this park; the runbook replay resets it.';

-- Parked rows the relay has not counted yet (operator parks), read on every tick.
CREATE INDEX ix_outbox_park_uncounted ON outbox_event (created_seq)
    WHERE parked_at IS NOT NULL AND park_counted = FALSE;
