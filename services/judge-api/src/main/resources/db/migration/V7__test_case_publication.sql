-- Retain historical zero-point cases allowed before Stage 6. They may be copied
-- into a draft for repair, but no newly sealed revision may contain them.
CREATE FUNCTION validate_positive_grading_tests() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.sealed_at IS NULL AND NEW.sealed_at IS NOT NULL
        AND EXISTS (SELECT 1 FROM test_cases WHERE assignment_revision_id = NEW.id AND points <= 0) THEN
        RAISE EXCEPTION 'All test case points must be positive before sealing' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER assignment_revision_positive_tests BEFORE UPDATE ON assignment_revisions
    FOR EACH ROW EXECUTE FUNCTION validate_positive_grading_tests();

-- Stage 5 allowed metadata-only publication. Require those assignments to be
-- reviewed and published again without changing any revision or submission.
UPDATE assignments a SET status = 'DRAFT', updated_at = CURRENT_TIMESTAMP
WHERE a.status = 'PUBLISHED' AND NOT EXISTS (
    SELECT 1 FROM assignment_revisions r
    WHERE r.assignment_id = a.id
      AND r.revision_number = (SELECT MAX(n.revision_number) FROM assignment_revisions n WHERE n.assignment_id = a.id)
      AND r.sealed_at IS NOT NULL
      AND r.title = a.title AND r.description = a.description
      AND r.maximum_score = a.maximum_score AND r.deadline = a.deadline
      AND EXISTS (SELECT 1 FROM test_cases t WHERE t.assignment_revision_id = r.id)
      AND NOT EXISTS (SELECT 1 FROM test_cases t WHERE t.assignment_revision_id = r.id AND t.points <= 0)
      AND (SELECT SUM(t.points) FROM test_cases t WHERE t.assignment_revision_id = r.id) = r.maximum_score
);

-- Execution order is unique within each assignment revision (V2). Historical
-- revisions intentionally reuse order numbers; current API operations use only
-- the latest revision of the URL's assignment.
CREATE INDEX test_cases_public_revision_order_idx
    ON test_cases(assignment_revision_id, execution_order) WHERE is_hidden = false;
