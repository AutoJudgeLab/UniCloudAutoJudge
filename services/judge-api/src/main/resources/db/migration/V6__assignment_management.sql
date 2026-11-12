ALTER TABLE assignments ADD CONSTRAINT assignments_positive_maximum_score CHECK (maximum_score > 0);
ALTER TABLE assignments ADD CONSTRAINT assignments_nonblank_description CHECK (btrim(description) <> '');

-- Revision metadata is a snapshot from creation, even before tests are sealed.
-- Editing assignment metadata must create a new numbered revision.
CREATE FUNCTION protect_revision_metadata() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.revision_number IS DISTINCT FROM OLD.revision_number
        OR NEW.title IS DISTINCT FROM OLD.title
        OR NEW.description IS DISTINCT FROM OLD.description
        OR NEW.maximum_score IS DISTINCT FROM OLD.maximum_score
        OR NEW.deadline IS DISTINCT FROM OLD.deadline
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Assignment revision metadata is immutable; create a new revision'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER assignment_revision_metadata_guard BEFORE UPDATE ON assignment_revisions
    FOR EACH ROW EXECUTE FUNCTION protect_revision_metadata();

CREATE INDEX assignments_teacher_created_id_idx ON assignments(teacher_id, created_at, id);
CREATE INDEX assignments_status_created_id_idx ON assignments(status, created_at, id);
