-- External identities never link by email and have no local password.
ALTER TABLE users ADD COLUMN external_issuer VARCHAR(512);
ALTER TABLE users ADD COLUMN external_subject VARCHAR(255);
ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;
ALTER TABLE users ALTER COLUMN email DROP NOT NULL;
ALTER TABLE users ADD CONSTRAINT users_identity_kind_check CHECK (
    (external_issuer IS NULL AND external_subject IS NULL AND password_hash IS NOT NULL AND email IS NOT NULL)
    OR (external_issuer IS NOT NULL AND btrim(external_issuer) <> ''
        AND external_subject IS NOT NULL AND btrim(external_subject) <> '' AND password_hash IS NULL)
);
CREATE UNIQUE INDEX users_external_identity_uq ON users(external_issuer, external_subject)
    WHERE external_issuer IS NOT NULL;
ALTER TABLE users DROP CONSTRAINT users_email_key;
DROP INDEX users_email_case_insensitive_uq;
CREATE UNIQUE INDEX users_email_case_insensitive_uq ON users(lower(email)) WHERE external_issuer IS NULL;
-- Provider role revocation must not be prevented by the local last-admin guard.
-- The guard still protects local administrators needed in local authentication mode.
CREATE OR REPLACE FUNCTION protect_last_active_administrator() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    was_active_admin BOOLEAN := FALSE;
    becomes_active_admin BOOLEAN := FALSE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        was_active_admin := OLD.role = 'ADMIN' AND OLD.enabled AND OLD.external_issuer IS NULL;
    END IF;
    IF TG_OP <> 'DELETE' THEN
        becomes_active_admin := NEW.role = 'ADMIN' AND NEW.enabled AND NEW.external_issuer IS NULL;
    END IF;
    IF was_active_admin OR becomes_active_admin THEN
        PERFORM pg_advisory_xact_lock(731024001);
    END IF;
    IF was_active_admin AND NOT becomes_active_admin THEN
        -- Lock surviving administrators as well. At stronger isolation levels,
        -- conflicting changes must serialize or abort, never use a stale count.
        PERFORM id FROM users WHERE role = 'ADMIN' AND enabled AND external_issuer IS NULL AND id <> OLD.id ORDER BY id FOR UPDATE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'The last active administrator cannot be removed, disabled or demoted'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;

-- Reject in-flight identities when another request updates their Portal role.
CREATE OR REPLACE FUNCTION advance_user_token_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.enabled IS DISTINCT FROM OLD.enabled
        OR (OLD.external_issuer IS NOT NULL AND NEW.role IS DISTINCT FROM OLD.role) THEN
        NEW.token_version := GREATEST(OLD.token_version + 1, NEW.token_version);
    ELSIF NEW.token_version < OLD.token_version THEN
        RAISE EXCEPTION 'User token versions cannot decrease' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
