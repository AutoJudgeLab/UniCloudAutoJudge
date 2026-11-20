ALTER TABLE submissions ADD COLUMN evaluation_lease_until TIMESTAMPTZ;
-- Old workers cannot renew; their attempts are reclaimable after deployment.
CREATE INDEX submissions_expired_lease_idx ON submissions(evaluation_lease_until)
    WHERE status = 'RUNNING';

ALTER TABLE submissions ADD CONSTRAINT submissions_lease_state CHECK (
    evaluation_lease_until IS NULL OR (status = 'RUNNING' AND evaluation_attempt_id IS NOT NULL));
