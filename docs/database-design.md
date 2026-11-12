# Database design

Flyway owns schema changes; Hibernate only validates. PostgreSQL UUID identifiers,
TIMESTAMPTZ/Instant UTC timestamps, NUMERIC(19,4)/BigDecimal scores, explicit checks,
foreign keys, and uniqueness constraints protect invariants. Existing V1–V9
migrations remain unchanged by Stage 10; V10 adds outbox delivery state.

| Table | Purpose |
| --- | --- |
| users | Identity, password hash, role, enabled status, token version |
| assignments | Editable teacher-owned assignment metadata |
| assignment_revisions | Immutable sealed grading configuration |
| test_cases | Revision-owned inputs, outputs, points, visibility, execution order |
| submissions | Source reference, immutable revision, lifecycle, evaluator attempt/version |
| submission_results | One aggregate result per evaluated submission |
| test_case_results | Unique submission/test outcomes from the same revision |
| outbox_events | Stable event identity, JSONB payload, publication/retry metadata |
| submission_requests | Student-scoped idempotency key, fingerprint, accepted submission |
| source_upload_cleanup | Durable cleanup intents/tombstones for failed or ambiguous PUTs |

There are ten domain tables plus Flyway's schema history. No cascading deletion
of historical grading is used. Accounts and accepted sources are never returned
as JPA entities. [The detailed schema](database-schema.md) describes earlier stages.

## Outbox V10

outbox_events retains id, aggregate_id, event_type, event_version, payload,
created_at, published_at, and attempt_count. V10 adds next_attempt_at,
last_error_code, and redis_record_id. A partial index orders due unpublished rows.
A trigger prevents changes to event ID/type/version/aggregate/payload/creation time.
The unique SUBMISSION_CREATED index permits only one creation event per submission.

Each delivery locks one due row until XADD and the state update complete. A failed
Redis call commits a sanitized error code and exponential next-attempt time;
a failed PostgreSQL update/commit rolls back and leaves the event retryable.
Attempt count includes committed attempts and saturates at the integer maximum;
attempts rolled back with a failed commit are not reflected in this count.

Published rows are retained. Neither Redis trimming nor evaluation completion
automatically deletes them. Back up PostgreSQL with tested point-in-time recovery.
An archive/pruning policy must retain replay capability and be designed before
purging rows. Monitor disk growth; indefinite retention is an initial deliberate
recovery policy, not a zero-cost storage solution.

[Replay procedure](reliability.md) changes only delivery state, preserving stable
IDs. Internal legacy outbox payloads may include source keys and hashes; these are
never forwarded wholesale. The wire contract is constructed from a strict whitelist
of identifiers. Credentials and source/test contents are never needed in events.

