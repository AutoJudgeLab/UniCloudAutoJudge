CREATE FUNCTION protect_assignment_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.sealed_at IS NOT NULL THEN
            RAISE EXCEPTION 'Sealed assignment revisions cannot be deleted' USING ERRCODE = '23514';
        END IF;
        RETURN OLD;
    END IF;
    IF TG_OP = 'INSERT' AND NEW.sealed_at IS NOT NULL THEN
        RAISE EXCEPTION 'Create a draft revision and its test cases before sealing' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF OLD.sealed_at IS NOT NULL AND NEW IS DISTINCT FROM OLD THEN
            RAISE EXCEPTION 'Sealed assignment revisions are immutable' USING ERRCODE = '23514';
        END IF;
        IF NEW.id <> OLD.id OR NEW.assignment_id <> OLD.assignment_id THEN
            RAISE EXCEPTION 'Revision identity cannot change' USING ERRCODE = '23514';
        END IF;
        IF OLD.sealed_at IS NULL AND NEW.sealed_at IS NOT NULL THEN
            IF NOT EXISTS (SELECT 1 FROM test_cases WHERE assignment_revision_id = NEW.id)
                OR (SELECT SUM(points) FROM test_cases WHERE assignment_revision_id = NEW.id) <> NEW.maximum_score THEN
                RAISE EXCEPTION 'A sealed revision must have test cases whose points sum to its maximum score'
                    USING ERRCODE = '23514';
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER assignment_revision_history_guard
    BEFORE INSERT OR UPDATE OR DELETE ON assignment_revisions
    FOR EACH ROW EXECUTE FUNCTION protect_assignment_revision();

CREATE FUNCTION protect_revision_test_cases() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    revision_id UUID;
    revision_sealed_at TIMESTAMPTZ;
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.id <> OLD.id OR NEW.assignment_revision_id <> OLD.assignment_revision_id
        OR NEW.assignment_id <> OLD.assignment_id) THEN
        RAISE EXCEPTION 'Test case identity and revision cannot change' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN revision_id := OLD.assignment_revision_id;
    ELSE revision_id := NEW.assignment_revision_id;
    END IF;
    -- Serialize test case writes with sealing, including inserts and deletes.
    SELECT sealed_at INTO revision_sealed_at FROM assignment_revisions WHERE id = revision_id FOR UPDATE;
    IF revision_sealed_at IS NOT NULL THEN
        RAISE EXCEPTION 'Test cases of a sealed revision are immutable' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER test_case_history_guard BEFORE INSERT OR UPDATE OR DELETE ON test_cases
    FOR EACH ROW EXECUTE FUNCTION protect_revision_test_cases();

CREATE FUNCTION protect_submission_configuration() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    revision_sealed_at TIMESTAMPTZ;
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.id <> OLD.id OR NEW.assignment_id <> OLD.assignment_id
        OR NEW.assignment_revision_id <> OLD.assignment_revision_id OR NEW.student_id <> OLD.student_id
        OR NEW.source_file_key <> OLD.source_file_key OR NEW.original_filename <> OLD.original_filename
        OR NEW.programming_language <> OLD.programming_language OR NEW.submitted_at <> OLD.submitted_at) THEN
        RAISE EXCEPTION 'Accepted submission identity, source and grading configuration cannot change'
            USING ERRCODE = '23514';
    END IF;
    SELECT sealed_at INTO revision_sealed_at FROM assignment_revisions
        WHERE id = NEW.assignment_revision_id FOR SHARE;
    IF FOUND AND revision_sealed_at IS NULL THEN
        RAISE EXCEPTION 'Submissions require a sealed assignment revision' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER submission_configuration_guard BEFORE INSERT OR UPDATE ON submissions
    FOR EACH ROW EXECUTE FUNCTION protect_submission_configuration();

CREATE FUNCTION validate_submission_result_score() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    revision_maximum NUMERIC;
    configured_tests INTEGER;
BEGIN
    SELECT r.maximum_score, (SELECT COUNT(*) FROM test_cases t WHERE t.assignment_revision_id = r.id)
        INTO revision_maximum, configured_tests
        FROM submissions s JOIN assignment_revisions r ON r.id = s.assignment_revision_id
        WHERE s.id = NEW.submission_id;
    IF FOUND AND (NEW.maximum_score <> revision_maximum OR NEW.total_tests <> configured_tests) THEN
        RAISE EXCEPTION 'Result maximum score and test count must match the submission revision'
            USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.submission_id <> OLD.submission_id THEN
        RAISE EXCEPTION 'Results cannot be moved to another submission' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER submission_result_score_guard BEFORE INSERT OR UPDATE ON submission_results
    FOR EACH ROW EXECUTE FUNCTION validate_submission_result_score();

CREATE FUNCTION validate_test_case_result_score() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.awarded_points > (SELECT points FROM test_cases WHERE id = NEW.test_case_id) THEN
        RAISE EXCEPTION 'Awarded points cannot exceed the original test case points' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.submission_id <> OLD.submission_id OR NEW.test_case_id <> OLD.test_case_id
        OR NEW.assignment_revision_id <> OLD.assignment_revision_id) THEN
        RAISE EXCEPTION 'Test case results cannot be moved to another submission or test case' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER test_case_result_score_guard BEFORE INSERT OR UPDATE ON test_case_results
    FOR EACH ROW EXECUTE FUNCTION validate_test_case_result_score();
