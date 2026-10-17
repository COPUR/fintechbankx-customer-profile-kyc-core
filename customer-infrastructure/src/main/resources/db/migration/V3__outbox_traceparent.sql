-- W3C trace context of the request that raised the event, sent as the Kafka
-- "traceparent" header so traces cross the broker. Null when none came in.
ALTER TABLE outbox_event ADD COLUMN traceparent VARCHAR(55);
