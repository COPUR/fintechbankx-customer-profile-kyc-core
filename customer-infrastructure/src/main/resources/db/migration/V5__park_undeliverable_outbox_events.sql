-- Outbox rows that can never be sent are parked instead of blocking every
-- later event. Classification follows ADR-021 decision 4: OutboxRelay parks a
-- row only for a payload error (RecordTooLarge, Serialization, InvalidTopic)
-- and continues; every other failure (retriable, authorization, SASL/IAM,
-- unclassified) stops the batch without marking the row and is retried with
-- backoff, never parked by the relay. An operator may park a row by hand
-- (runbook "Parked outbox events"). The relay skips parked rows and the later
-- rows of the same customer, keeps relaying other customers, and the
-- outbox_parked_events gauge counts them. Parked rows are never purged and are
-- replayed by hand. last_error (V2) keeps the reason. first_failed_at is no
-- longer written (the 24 h park ceiling it served was removed by ADR-021).
-- Comment-only edit after release: V5 has run only in CI and ephemeral
-- databases (review 5456301261), so the changed Flyway checksum affects no
-- persistent environment.

ALTER TABLE outbox_event ADD COLUMN parked_at TIMESTAMPTZ;
ALTER TABLE outbox_event ADD COLUMN first_failed_at TIMESTAMPTZ;

COMMENT ON COLUMN outbox_event.parked_at IS 'Set when the relay gave up on the row; NULL again after a manual replay.';
COMMENT ON COLUMN outbox_event.first_failed_at IS 'First failed send; the retryable park ceiling counts from here. NULL again after a manual replay.';
COMMENT ON COLUMN outbox_event.last_error IS 'Last send failure: exception class and message.';

-- The relay reads rows that are neither published nor parked, in insertion order.
DROP INDEX ix_outbox_unpublished;
CREATE INDEX ix_outbox_unpublished ON outbox_event (created_seq) WHERE published_at IS NULL AND parked_at IS NULL;
-- Parked rows by customer, for the hold-back check and the runbook queries.
CREATE INDEX ix_outbox_parked ON outbox_event (aggregate_type, aggregate_id, created_seq)
    WHERE published_at IS NULL AND parked_at IS NOT NULL;
