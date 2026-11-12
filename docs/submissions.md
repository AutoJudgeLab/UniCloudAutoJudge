# Student submission management

Stage 8 accepts source files for published assignments. The API validates source,
stores it privately in MinIO, and commits a PENDING submission and a versioned
SUBMISSION_CREATED outbox event. It does not compile, execute, or grade code.

## Endpoints and authorization

| Endpoint | Access and response |
| --- | --- |
| `POST /api/assignments/{assignmentId}/submissions` | STUDENT only; multipart upload; 201 after durable acceptance, or an accepted retry. |
| `GET /api/submissions/{submissionId}` | Owning STUDENT, assignment-owning TEACHER, or ADMIN. |
| `GET /api/users/me/submissions` | Authenticated user's own submissions, identified from the security context. |
| `GET /api/assignments/{assignmentId}/submissions` | STUDENT sees only their own; owning TEACHER and ADMIN see all for that assignment. |

All endpoints require a bearer JWT. Private detail reads outside the caller's
scope return 404. Students with existing submissions retain access to their
history after an assignment becomes draft or archived. Source object keys, source
content, password data, and grading test data are never included in these DTOs.
Source-download endpoints are deferred. [Stage 9](evaluation-results.md) adds evaluation-result retrieval.

POST accepts exactly one `file` part and one `programmingLanguage` part. It also
requires an `Idempotency-Key` header containing 1–128 ASCII letters/digits or
`._:-`. Unknown/duplicate multipart parts and all POST query parameters are
rejected, including client-supplied student IDs or timestamps. The student comes
from the authenticated local identity.

```sh
curl -i 'http://localhost:8080/api/assignments/<assignmentId>/submissions' \
  -H 'Authorization: Bearer <studentAccessToken>' \
  -H 'Idempotency-Key: <fresh-client-generated-key>' \
  -F 'programmingLanguage=JAVA' \
  -F 'file=@Main.java'
```

Keep the key and exact request for retries. The response contains exactly:

```json
{
  "submissionId": "<uuid>",
  "assignmentId": "<uuid>",
  "status": "PENDING",
  "submittedAt": "2030-06-30T12:00:00.123456Z"
}
```

Location identifies `/api/submissions/{submissionId}`. GET detail/list DTOs add
assignmentRevisionId, studentId, programmingLanguage, originalFilename, sourceSize,
startedAt, completedAt, and attemptCount. Legacy rows may have a null sourceSize.

## File and language validation

| programmingLanguage | Required extension |
| --- | --- |
| JAVA | java |
| PYTHON | py |
| C | c |
| CPP | cpp |
| JAVASCRIPT | js |
| TYPESCRIPT | ts |
| KOTLIN | kt |
| GO | go |
| RUST | rs |
| CSHARP | cs |

Language values are case-sensitive. Extensions are case-insensitive and must also
be enabled by `S3_ALLOWED_EXTENSIONS`. This is an acceptance format list, not a
promise that evaluators for those languages exist yet.

[Storage validation](storage.md) requires nonempty UTF-8 source text, a safe
filename, and a positive size within S3_MAX_UPLOAD_SIZE. Client MIME types are
ignored. File data is staged on disk, validated, and hashed with SHA-256 using
bounded buffers. The validated stage is reused for S3 upload, avoiding another
full copy or compilation. Original filenames are preserved as metadata, while
keys are generated under `submissions/{submissionId}/source/{uuid}.{extension}`.

HTTP multipart storage uses a zero-byte memory threshold. Configure both
S3_MAX_UPLOAD_SIZE and the larger S3_MAX_REQUEST_SIZE envelope limit. A whole
request may be rejected by the servlet container before file-level validation.

## Deadline and acceptance policy

PostgreSQL `clock_timestamp()` is authoritative. The API checks publication, the
latest sealed grading revision, and the deadline before initiating a new upload.
After MinIO acknowledges the complete source file, the API locks and rechecks the
current account, assignment status, grading revision, and deadline.

The decision timestamp must be strictly earlier than the revision deadline. That
server timestamp becomes submittedAt. Uploads starting before but finishing after
the deadline are rejected with 409 DEADLINE_PASSED and their objects are reconciled.
A grading revision change during upload requires retry; the API does not silently
accept against different tests. Disabled or demoted students cannot complete acceptance.

The submission is **accepted only when the database transaction commits**, after
the successful S3 acknowledgement. The commit may finish after the deadline if
the post-upload decision timestamp was before it. No 201 response is emitted
before commit. Client timestamps have no effect. A lost HTTP response can be
recovered with the same idempotency key, even after the deadline or archiving.

## Idempotency and partial failure recovery

Keys are scoped to a student across assignments. A fingerprint binds the key to
assignment UUID, canonical language, exact original filename, and exact source
bytes (SHA-256). Equivalent retries return the original submission and original
four-field PENDING acceptance response. GET exposes the current lifecycle status.
Reusing a key with different valid content, filename, language, or assignment
returns 409 IDEMPOTENCY_CONFLICT. Invalid inputs still receive validation errors.

A reservation transaction creates/locks a submission_requests row and registers
the fresh planned object key in source_upload_cleanup before any S3 write. This
intent commits independently of acceptance. Concurrent equivalent requests
serialize on that request row and replay the winner instead of uploading twice.
Failed attempts retain the fingerprint and submission UUID; a retry uses a new
object key, so a failed attempt can never overwrite the accepted source.

During upload, one database connection and the idempotency row lock remain held.
No assignment/user write lock or second connection is held across remote I/O.
After S3 succeeds, the final checks and inserts use that same transaction.
Submission metadata, the outbox event, the accepted idempotency reference, and
removal of the successful cleanup intent commit together. Pool capacity and S3
timeouts bound concurrent work; this stage performs no CPU-heavy evaluation.

There is no distributed transaction between PostgreSQL and MinIO. On failure, a
best-effort cleanup follows rollback. Scheduled reconciliation retries durable
intents after crashes, connection failures, or lost S3 acknowledgements. It locks
the request before checking PostgreSQL and never deletes an object referenced by
the accepted submission. Active uploads are skipped using SKIP LOCKED. An
ambiguous database commit is resolved by the persisted request/submission state,
not by assuming rollback.

Unaccepted object keys retain cleanup tombstones even after successful deletion
or a missing-object response. This handles a delayed PUT that completes after its
database connection was lost. Tombstones are retried periodically; do not prune
them without a separate retention/reconciliation design that accounts for late
writes. Accepted idempotency keys are also retained without automatic expiry.
No publicly callable cleanup/admin endpoint is added.

MinIO separately reclaims abandoned multipart parts after
MINIO_STALE_UPLOADS_EXPIRY (24h), scanning at MINIO_STALE_UPLOADS_CLEANUP_INTERVAL
(1h) in Compose. For another S3 provider, configure equivalent incomplete-upload
cleanup without adding expiration for completed accepted sources.

| Environment variable | Default |
| --- | --- |
| SUBMISSION_CLEANUP_ENABLED | true |
| SUBMISSION_CLEANUP_INTERVAL | PT1M |
| SUBMISSION_CLEANUP_INITIAL_DELAY | 15m |
| SUBMISSION_CLEANUP_RETRY_DELAY | 1h |
| SUBMISSION_CLEANUP_BATCH_SIZE | 20 (1–100) |

Initial/retry delays must be between one second and seven days. Leave cleanup
enabled in deployed instances; row locks allow multiple instances to reconcile
without deleting active or committed sources. Each batch is bounded.

## Queries and events

Both list endpoints also support submittedFrom (inclusive) and submittedBefore (exclusive)
ISO-8601 date filters. See [Stage 9](evaluation-results.md). They accept page (default 0), size (default 20, maximum 100), status,
programmingLanguage, assignmentId, and a single `sort=field,asc|desc`. Allowed sort
fields are id, submittedAt, startedAt, completedAt, status, and programmingLanguage.
The default is submittedAt,desc with ID as a stable tie-breaker. Filters always
intersect with ownership and any assignment path parameter. Responses contain
content, page, size, totalElements, and totalPages.

The judge-messaging module defines SubmissionCreatedV1. Its event type is
SUBMISSION_CREATED, version 1. The outbox payload contains submissionId,
assignmentId, assignmentRevisionId, studentId, sourceFileKey, programmingLanguage,
submittedAt, sourceSha256, and sourceSize. PublishedAt remains null and attempts
remain zero. A unique partial index prevents duplicate creation events per
submission. Stage 10 publishes identifier-only Redis events; worker consumption remains deferred.

Flyway V8 adds durable requests/cleanup tables, nullable source size/hash fields
for compatibility with existing submissions, immutable source metadata protection,
and query/outbox indexes. V1–V7 are unchanged. Every new accepted submission
references the exact sealed revision checked after upload.

Validation errors use 400, missing/hidden resources 404, oversized files 413, and
idempotency/deadline/grading conflicts 409. Authentication/role errors use 401/403.
Storage or submission persistence failures use sanitized 503 responses. Retry
the same request/key after uncertain failures; do not generate a fresh key merely
because a response was lost.

## Verification and deferred work

```sh
mvn clean verify
mvn clean verify -Pintegration-tests
```

SubmissionManagementIT starts actual PostgreSQL and MinIO containers and the HTTP
API. Tests exercise multipart validation, ownership, sorting/filtering, deadline
crossings, concurrent idempotency, source and outbox persistence, account/grading
changes during upload, database rollback, lost PUT acknowledgements, and cleanup
of late objects without removing active/accepted sources.

[Stage 9](evaluation-results.md) implements result retrieval and leaderboards. Automatic
execution, worker behavior, and source-download endpoints remain deferred. Stage 10 implements Redis outbox publishing.
