ALTER TABLE submissions ADD COLUMN source_size BIGINT CHECK (source_size > 0);
ALTER TABLE submissions ADD COLUMN source_sha256 VARCHAR(64) CHECK (source_sha256 ~ '^[0-9a-f]{64}$');

CREATE FUNCTION protect_submission_source_metadata() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.source_size IS DISTINCT FROM OLD.source_size OR NEW.source_sha256 IS DISTINCT FROM OLD.source_sha256 THEN
        RAISE EXCEPTION 'Accepted source metadata is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER submission_source_metadata_guard BEFORE UPDATE ON submissions
    FOR EACH ROW EXECUTE FUNCTION protect_submission_source_metadata();

CREATE TABLE submission_requests (
    id UUID PRIMARY KEY,
    student_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    assignment_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{1,128}$'),
    fingerprint VARCHAR(64) NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    accepted_submission_id UUID UNIQUE REFERENCES submissions(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT submission_requests_identity UNIQUE (student_id, idempotency_key),
    CONSTRAINT submission_requests_accepted_id CHECK (accepted_submission_id IS NULL OR accepted_submission_id = id)
);
-- Assignment ID is an intent, not an accepted historical reference. A failed
-- request must not prevent deleting an assignment without submissions.

CREATE TABLE source_upload_cleanup (
    object_key VARCHAR(1024) PRIMARY KEY,
    request_id UUID NOT NULL REFERENCES submission_requests(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    attempt_count BIGINT NOT NULL DEFAULT 0 CHECK (attempt_count >= 0)
);
CREATE INDEX source_upload_cleanup_due_idx ON source_upload_cleanup(next_attempt_at, object_key);
CREATE INDEX source_upload_cleanup_request_idx ON source_upload_cleanup(request_id);
CREATE INDEX submissions_student_status_time_idx ON submissions(student_id, status, submitted_at, id);
CREATE INDEX submissions_assignment_status_time_idx ON submissions(assignment_id, status, submitted_at, id);
CREATE UNIQUE INDEX outbox_submission_created_once_idx ON outbox_events(aggregate_id)
    WHERE event_type = 'SUBMISSION_CREATED';
