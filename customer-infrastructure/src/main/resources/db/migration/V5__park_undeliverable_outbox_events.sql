-- Outbox rows that can never be sent are parked instead of blocking every
-- later event. OutboxRelay parks a row when Kafka refuses it permanently
-- (RecordTooLarge, Serialization, InvalidTopic, TopicAuthorization, any error
-- that is not retriable) at once. A retryable failure (RetriableException,
-- timeout) never parks by count: only when the row has kept failing for longer
-- than customer.outbox.relay.retryable-park-after (default 24 h) since
-- first_failed_at, so a broker or egress outage does not need a manual replay. The relay skips parked rows and the later rows of the same
-- customer, keeps relaying other customers, and the outbox_parked_events gauge
-- counts them. Parked rows are never purged and are replayed by hand (runbook
-- "Parked outbox events"). last_error (V2) keeps the reason.

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
