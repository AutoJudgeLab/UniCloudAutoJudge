ALTER TABLE users ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0 CHECK (token_version >= 0);

-- Keep invalidation atomic with status changes, including writes outside JPA.
CREATE FUNCTION advance_user_token_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.enabled IS DISTINCT FROM OLD.enabled THEN
        NEW.token_version := GREATEST(OLD.token_version + 1, NEW.token_version);
    ELSIF NEW.token_version < OLD.token_version THEN
        RAISE EXCEPTION 'User token versions cannot decrease' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER users_token_version_guard BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION advance_user_token_version();

CREATE INDEX users_created_at_id_idx ON users(created_at, id);
CREATE INDEX users_role_enabled_idx ON users(role, enabled);
