ALTER TABLE outbox_events ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE outbox_events ADD COLUMN last_error_code VARCHAR(32);
ALTER TABLE outbox_events ADD COLUMN redis_record_id VARCHAR(64);
CREATE INDEX outbox_events_due_idx ON outbox_events(next_attempt_at, created_at, id) WHERE published_at IS NULL;

CREATE FUNCTION protect_outbox_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.id, NEW.aggregate_id, NEW.event_type, NEW.event_version, NEW.payload, NEW.created_at)
        IS DISTINCT FROM (OLD.id, OLD.aggregate_id, OLD.event_type, OLD.event_version, OLD.payload, OLD.created_at) THEN
        RAISE EXCEPTION 'Outbox event identity and payload are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER outbox_identity_guard BEFORE UPDATE ON outbox_events
    FOR EACH ROW EXECUTE FUNCTION protect_outbox_identity();

