-- Fail rather than silently merge any pre-existing case-insensitive duplicates.
CREATE UNIQUE INDEX users_username_case_insensitive_uq ON users(lower(username));
CREATE UNIQUE INDEX users_email_case_insensitive_uq ON users(lower(email));

-- The same transaction lock is used by the initial administrator bootstrap.
CREATE FUNCTION protect_last_active_administrator() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    was_active_admin BOOLEAN := FALSE;
    becomes_active_admin BOOLEAN := FALSE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        was_active_admin := OLD.role = 'ADMIN' AND OLD.enabled;
    END IF;
    IF TG_OP <> 'DELETE' THEN
        becomes_active_admin := NEW.role = 'ADMIN' AND NEW.enabled;
    END IF;
    IF was_active_admin OR becomes_active_admin THEN
        PERFORM pg_advisory_xact_lock(731024001);
    END IF;
    IF was_active_admin AND NOT becomes_active_admin THEN
        -- Lock surviving administrators as well. At stronger isolation levels,
        -- conflicting changes must serialize or abort, never use a stale count.
        PERFORM id FROM users WHERE role = 'ADMIN' AND enabled AND id <> OLD.id ORDER BY id FOR UPDATE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'The last active administrator cannot be removed, disabled or demoted'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER users_last_active_administrator_guard
    BEFORE INSERT OR UPDATE OR DELETE ON users
    FOR EACH ROW EXECUTE FUNCTION protect_last_active_administrator();
