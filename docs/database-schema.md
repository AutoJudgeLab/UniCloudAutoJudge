# Database schema and grading history

Stage 2 adds eight PostgreSQL tables and their JPA entities/repositories inside
`judge-api`, organized into `user`, `assignment`, `testcase`, `submission`,
`result`, and `outbox` packages. The shared modules contain no JPA types. No new
business controllers, evaluators, or outbox publishers are added by that stage.
Stage 3 adds authentication separately; see [Authentication](authentication.md).
Stage 4 adds [User administration](user-administration.md) and token invalidation.
Stage 5 adds [Assignment management](assignments.md), with assignment types in
`assignment/domain`, repositories in `assignment/persistence`, and separate
`assignment/api` and `assignment/application` layers.
Stage 6 adds [Test-case management](test-cases.md) in `testcase/api`,
`testcase/application`, `testcase/domain`, and `testcase/persistence`.
Stage 8 adds [Submissions](submissions.md), source fingerprints, durable idempotency
and cleanup records, and atomic submission/outbox insertion.

## Migrations and data types

- V1 remains unchanged, preserving existing Flyway checksums.
- V2 creates the domain tables, constraints, and indexes.
- V3 adds database triggers that protect revision history and validate result
  references against the original grading configuration.
- V4 adds case-insensitive identity indexes and last-active-administrator protection.
- V5 adds the user token-version counter, automatic advancement on status changes,
  and indexes for user listing/filtering. It preserves existing account data.
- V6 requires positive assignment maximum scores and nonblank descriptions,
  makes revision metadata immutable even before sealing, and indexes assignment
  listings by owner/status and creation time. Existing zero-score or blank-description
  assignment rows must be explicitly corrected before upgrading; migration fails
  rather than silently rewriting them. Existing historical revision metadata is retained.
- V7 requires positive test points when sealing new revisions, adds a partial index
  for public test reads, and returns legacy publications lacking valid sealed grading
  to DRAFT for review. No historical tests, revisions, submissions, or results change.
- V8 adds submission source size/hash, immutable source metadata, submission_requests
  and source_upload_cleanup, query indexes, and uniqueness for creation outbox events.
  Existing submissions retain nullable source size/hash; no historical source is guessed.

Hibernate remains configured with `ddl-auto=validate`. Application startup applies
Flyway migrations before Hibernate validates the mappings. Do not edit applied
migrations; add subsequent versions for future changes.

Identifiers are UUIDs. New submissions receive a server-generated UUID in the
durable reservation before uploading; other entities use JPA generation. SQL callers supply UUIDs explicitly.
Timestamps use PostgreSQL `TIMESTAMPTZ` and Java `Instant`; PostgreSQL stores
microsecond precision. JPA persistence callbacks initialize creation timestamps
and update user/assignment modification timestamps. SQL inserts also have
timestamp defaults; direct SQL updates must maintain `updated_at` themselves.

Scores use `NUMERIC(19,4)` / `BigDecimal`, allowing fifteen integer digits and four
fractional digits. Assignment maximum scores must be positive. Other scores, counts,
attempts, and durations cannot be negative;
numeric NaN is rejected. Empty input/expected output are valid. Stage 6 API test
writes require positive points. Legacy zero-point tests remain valid stored history
and may be copied into drafts for repair; V7 prevents sealing such drafts. The
entity and original nonnegative database constraint intentionally retain that legacy
representation, while the API and publication checks require positive values.
Bean Validation rejects Java score values exceeding the declared precision/scale.
Direct SQL uses PostgreSQL's numeric coercion rules, including scale rounding.

| Table | Purpose and principal constraints |
| --- | --- |
| `users` | Unique username and email; nonblank password hash; STUDENT, TEACHER, or ADMIN; enabled by default. |
| `assignments` | Editable assignment metadata, owner, maximum score, deadline, and DRAFT/PUBLISHED/ARCHIVED status. |
| `assignment_revisions` | Numbered copies of title, description, maximum score, and deadline; unique number per assignment; nullable seal timestamp. |
| `test_cases` | Test input/output, points, visibility, and zero-based execution order within a revision; unique order per revision. |
| `submissions` | Student, source object key, filename, language identifier, lifecycle status/timestamps, attempts, and exact revision. |
| `submission_results` | At most one aggregate result per submission; score cannot exceed its maximum, passed tests cannot exceed total tests. |
| `test_case_results` | At most one result per submission/test pair; composite foreign keys require both to belong to the same revision. |
| `outbox_events` | Versioned event type, aggregate UUID, JSONB object payload, publication time, and attempts; unique SUBMISSION_CREATED per submission. |
| `submission_requests` | Student-scoped unique idempotency key, request fingerprint, planned submission UUID, optional committed submission reference. |
| `source_upload_cleanup` | Durable planned object key, request reference, next cleanup time and attempt count; retained tombstones for unaccepted sources. |

Foreign keys are non-null for required relationships and use `ON DELETE RESTRICT`.
JPA relationships are lazy and have no cascade removal or orphan removal. Entities
are internal persistence models, never API responses. Password hashes are also
excluded from Jackson serialization as an additional safeguard.

V4 enforces case-insensitive username/email uniqueness. Stage 3 normalizes new
identities, hashes passwords, provisions the initial administrator, and protects
the last active administrator. Repositories do not constitute an authorization
boundary. Stage 4 authorizes administrative operations at both the HTTP and service
layers; Stage 5 also enforces assignment ownership in its application service.
Future business services must enforce their own resource ownership. Programming language remains a nonblank
string rather than introducing an unrequested language support matrix.

`test_case_results.status` uses the same `Verdict` vocabulary as aggregate results:
ACCEPTED, WRONG_ANSWER, COMPILATION_ERROR, RUNTIME_ERROR, TIME_LIMIT_EXCEEDED,
MEMORY_LIMIT_EXCEEDED, and INTERNAL_ERROR. Tests not executed have no result row.
Lifecycle states PENDING/RUNNING/COMPLETED/FAILED/CANCELLED belong to submissions.
Stage 9 provides authorized attempt start/completion transitions. Worker scheduling,
retry orchestration, and execution logic remain deferred.

## Immutable grading configuration

The editable assignment row is not used to grade an existing submission. Each
submission references `assignment_revision_id`, which is mandatory and immutable.
This protects every accepted submission, including one still pending evaluation;
it does not depend on achieving an ACCEPTED verdict.

To prepare a revision in a transaction:

1. Create an `AssignmentRevision` from the assignment metadata and a new revision
   number. Persist the draft revision.
2. Add its test cases. Each test case carries both assignment and revision IDs;
   a composite foreign key rejects mismatched assignments.
3. Flush the test cases before calling `seal()` and flushing the revision. The
   database requires at least one case and a point sum equal to the revision's
   maximum score. A revision cannot be inserted already sealed.
4. The submission service requires PUBLISHED status and the newest
   revision to be sealed, then accepts against that exact revision. It rechecks
   grading and the deadline after S3 upload. Never fall back
   to an older sealed revision when a newer unsealed configuration exists. Composite foreign keys
   also reject submissions whose assignment does not match the revision.

Sealing locks the revision row. All test-case inserts, updates, and deletes lock
that same row, serializing edits with sealing under the application's default
PostgreSQL READ COMMITTED isolation. Database triggers then reject any change to
a sealed revision or its test cases, including adding cases, removing cases,
changing hidden status/order, and unsealing the revision. A repository method
provides a pessimistic revision lock for future application workflows. Callers
must flush draft edits before sealing and handle transaction failures/retries.

V6 makes revision metadata immutable from creation; only test cases remain editable
until sealing. Stage 5 metadata edits append revisions and copy test cases unchanged.
Published metadata edits require explicit `createNewRevision=true` and return to DRAFT.
Stage 6 test-case edits automatically fork published/sealed revisions and return to
DRAFT. Publication now validates and seals all tests atomically before exposing the
assignment to students.

An assignment change requires a new revision for future submissions. Existing
submissions continue to reference their original title, description, deadline,
maximum score, test data, point weights, visibility, and order. Old revisions
cannot be deleted once sealed, even when currently unreferenced. Draft cases/revisions can be
explicitly removed in dependency order.

Submission owner, source key, original filename, language, submission time, and
assignment/revision references cannot be changed after insertion. The storage
integration uses a new object key per attempt and never overwrites accepted source
keys. Stage 8 reconciliation protects committed objects and retries unaccepted keys.

Aggregate result maximum score and total test count must match the submission's
sealed revision. Per-test awarded points cannot exceed that original case's points.
Result rows cannot be moved to a different submission or test case. Aggregate
score calculation and verdict selection remain responsibilities of the future
evaluator. Stage 9 validates agreement between supplied summary/per-test results
before atomically committing completion; no evaluations are generated by the API.

There is no cascade deletion of historical submissions/results. Explicit result
retention and administrative deletion policies are deferred to authorized services.

## Indexes and repositories

Indexes support assignments by teacher/status/deadline, submissions by assignment
or student and time, submissions by status/time or revision, and test cases ordered
within a revision. Unique constraints provide supporting indexes for revision
numbers, aggregate results, and per-test results. Other foreign-key lookups have
supporting indexes. The unpublished outbox index is partial on `published_at IS
NULL`, ordered by creation time and ID; aggregate IDs have a separate index.

Outbox aggregate IDs deliberately have no entity foreign key: events can describe
different aggregate types. Stage 8 inserts SUBMISSION_CREATED alongside each accepted
submission in one transaction. Event payloads must be JSON objects. Stage 10 adds
publication, row locking, retry scheduling, and replay metadata. Redis consumers
remain deferred; see [database design](database-design.md) and [reliability](reliability.md).

## Verification

Run the standard reactor build, then the Docker-backed integration suite:

```sh
mvn clean verify
mvn clean verify -Pintegration-tests
```

The PostgreSQL tests cover a fresh schema, upgrade from V1, repeated migration
validation, Hibernate mapping validation, repository CRUD/query operations,
UUID/Instant/BigDecimal/JSONB round trips, enum values, defaults, Bean Validation,
password-hash serialization exclusion, unique/foreign-key/check constraints,
historical snapshots, forbidden deletions, and concurrent sealing/test-case edits.
The existing health/security and PostgreSQL/Redis startup tests continue to run.
No local `.env` credentials or pre-existing databases are needed for these tests.

## Stage 9 evaluation completion

V9 adds optimistic submission versions, evaluator attempt IDs, completion fingerprints,
immutable completed-result guards, and an assignment leaderboard index. No result rows
are generated by migration or startup. See [Evaluation results](evaluation-results.md)
for the internal secure boundary, report validation, and SQL eligibility rules.
