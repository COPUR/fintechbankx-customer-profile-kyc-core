-- One Kafka topic per aggregate (ADR-019 s1 and s8, owner decision 2026-10-08).
-- Every customer event goes to evt.cus.customer.v1, keyed by customer id and
-- named by its eventType (envelope and record header). Rows written before
-- this change hold a per-event topic (evt.cus.customer.created.v1, ...).
-- Rows still to be sent, pending or parked, move to the aggregate topic so a
-- later relay or replay sends them there; published rows keep the topic they
-- were sent to until the retention purge removes them.

UPDATE outbox_event
   SET topic = 'evt.cus.customer.v1'
 WHERE published_at IS NULL
   AND topic <> 'evt.cus.customer.v1';

-- New rows and unsent rows carry only the aggregate topic.
ALTER TABLE outbox_event DROP CONSTRAINT ck_outbox_topic_namespace;
ALTER TABLE outbox_event ADD CONSTRAINT ck_outbox_topic_aggregate
    CHECK (topic = 'evt.cus.customer.v1' OR published_at IS NOT NULL);

COMMENT ON COLUMN outbox_event.topic IS 'Kafka topic: evt.cus.customer.v1 for every customer event (ADR-019); published rows from before V11 keep their per-event topic.';
