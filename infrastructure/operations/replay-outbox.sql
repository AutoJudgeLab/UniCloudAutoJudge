-- Operator-only recovery. Supply UTC since/before; default is read-only preview.
\set ON_ERROR_STOP on
\if :{?since}
\else
  \echo 'Supply -v since=<UTC timestamp> and -v before=<UTC timestamp>'
  \quit
\endif
\if :{?before}
\else
  \echo 'Supply -v before=<UTC timestamp>'
  \quit
\endif
\if :{?apply}
\else
  \set apply false
\endif
\if :{?batch_size}
\else
  \set batch_size 100
\endif
\if :{?after_id}
\else
  \set after_id '00000000-0000-0000-0000-000000000000'
\endif
BEGIN;
SET LOCAL lock_timeout = '5s';
\if :apply
WITH selected AS (
    SELECT e.id FROM outbox_events e JOIN submissions s ON s.id = e.aggregate_id
    WHERE e.published_at IS NOT NULL AND e.event_type = 'SUBMISSION_CREATED' AND s.status = 'PENDING'
        AND e.created_at >= :'since'::timestamptz AND e.created_at < :'before'::timestamptz
        AND e.id > :'after_id'::uuid
    ORDER BY e.id LIMIT LEAST(GREATEST(:'batch_size'::int, 1), 1000) FOR UPDATE OF e
), replayed AS (
    UPDATE outbox_events e SET published_at = NULL, next_attempt_at = clock_timestamp(),
        last_error_code = NULL, redis_record_id = NULL
    FROM selected WHERE e.id = selected.id RETURNING e.id, e.aggregate_id
)
SELECT * FROM replayed ORDER BY id;
\else
SELECT e.id, e.aggregate_id FROM outbox_events e JOIN submissions s ON s.id = e.aggregate_id
WHERE e.published_at IS NOT NULL AND e.event_type = 'SUBMISSION_CREATED' AND s.status = 'PENDING'
    AND e.created_at >= :'since'::timestamptz AND e.created_at < :'before'::timestamptz
    AND e.id > :'after_id'::uuid
ORDER BY e.id LIMIT LEAST(GREATEST(:'batch_size'::int, 1), 1000);
\endif
COMMIT;

