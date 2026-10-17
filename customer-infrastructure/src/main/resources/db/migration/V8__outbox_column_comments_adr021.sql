-- Column comments after ADR-021 decision 4: the relay parks only payload
-- errors (an operator may park by hand) and no longer writes first_failed_at.
COMMENT ON COLUMN outbox_event.parked_at IS 'Set for a payload error the relay cannot send, or by an operator (manual park); NULL again after a manual replay.';
COMMENT ON COLUMN outbox_event.first_failed_at IS 'No longer written (ADR-021 decision 4 removed the 24 h park ceiling); kept for rows from before that change.';
COMMENT ON COLUMN outbox_event.created_at IS 'When the row was written; the outbox_oldest_pending_age_seconds gauge measures from here.';
