ALTER TABLE submissions ADD COLUMN version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0);
ALTER TABLE submissions ADD COLUMN evaluation_attempt_id UUID;
ALTER TABLE submissions ADD COLUMN evaluation_fingerprint VARCHAR(64)
    CHECK (evaluation_fingerprint ~ '^[0-9a-f]{64}$');
ALTER TABLE submissions ADD CONSTRAINT completed_evaluation_identity CHECK (
    evaluation_fingerprint IS NULL OR (evaluation_attempt_id IS NOT NULL AND status = 'COMPLETED'
        AND started_at IS NOT NULL AND completed_at IS NOT NULL));
CREATE UNIQUE INDEX submissions_evaluation_attempt_idx ON submissions(evaluation_attempt_id)
    WHERE evaluation_attempt_id IS NOT NULL;
CREATE INDEX submissions_leaderboard_idx ON submissions(assignment_id, student_id, completed_at, id)
    WHERE status = 'COMPLETED';

CREATE FUNCTION protect_completed_evaluation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.evaluation_fingerprint IS NOT NULL AND
        (NEW.status, NEW.started_at, NEW.completed_at, NEW.attempt_count, NEW.evaluation_attempt_id, NEW.evaluation_fingerprint)
        IS DISTINCT FROM
        (OLD.status, OLD.started_at, OLD.completed_at, OLD.attempt_count, OLD.evaluation_attempt_id, OLD.evaluation_fingerprint) THEN
        RAISE EXCEPTION 'Completed evaluations are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER completed_evaluation_guard BEFORE UPDATE ON submissions
    FOR EACH ROW EXECUTE FUNCTION protect_completed_evaluation();

CREATE FUNCTION protect_completed_result() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    owner_id UUID;
    fingerprint VARCHAR;
BEGIN
    IF TG_OP = 'INSERT' THEN owner_id := NEW.submission_id; ELSE owner_id := OLD.submission_id; END IF;
    SELECT evaluation_fingerprint INTO fingerprint FROM submissions WHERE id = owner_id FOR UPDATE;
    IF fingerprint IS NOT NULL THEN
        RAISE EXCEPTION 'Completed evaluation results are immutable' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER completed_summary_guard BEFORE INSERT OR UPDATE OR DELETE ON submission_results
    FOR EACH ROW EXECUTE FUNCTION protect_completed_result();
CREATE TRIGGER completed_test_result_guard BEFORE INSERT OR UPDATE OR DELETE ON test_case_results
    FOR EACH ROW EXECUTE FUNCTION protect_completed_result();

