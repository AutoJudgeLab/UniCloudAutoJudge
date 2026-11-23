# Evaluation results, history, and leaderboards

Stage 9 retrieves stored evaluations and prepares an internal completion boundary.
It does not run source code, simulate a grader, seed results, or implement judge-worker.
Stage 10 separately publishes identifier-only Redis events. Newly accepted submissions remain PENDING until a
future trusted evaluator supplies a real report.

## HTTP API

All routes require the existing bearer JWT.

| Route | Behavior |
| --- | --- |
| GET /api/submissions/{submissionId}/result | Owning student, assignment-owning teacher, or administrator. Others receive 404. |
| GET /api/assignments/{assignmentId}/leaderboard | All authenticated users for published assignments. Owning teachers/admins can also read draft/archived assignments. |
| GET /api/assignments/{assignmentId}/submissions | Students see only their own; owning teachers/admins see the assignment's history. |
| GET /api/users/me/submissions | The authenticated user's own history. |

Administrators can inspect any assignment's history; teachers can inspect their
own assignments. The /me route always means the current user, including for
administrators. No user-supplied student identity changes that scope.

Result responses contain submissionId, status, verdict, totalScore, maximumScore,
passedTests, totalTests, executionTimeMs, submittedAt, completedAt, and testResults.
For PENDING/RUNNING (or missing stored results), unavailable evaluation fields are
null. A legacy COMPLETED row without a summary also returns null grades; retrieval
never fills them in. Empty testResults on a stored compilation failure means that
no individual tests ran, not that any tests passed. Unknown timings remain null.

Each stored test outcome exposes testCaseId, status, awardedPoints, and
executionTimeMs. inputData, expectedOutput, and actualOutput are returned for
public tests and for authorized teachers/admins. All three are null for hidden
tests viewed by students. Visibility and scoring use the submission's immutable
revision, including after the assignment is edited or archived. Source keys and
security credentials are never included.

## History filters

Existing page, size, sort, status, programmingLanguage, and assignmentId filters
are retained. Add ISO-8601 instants submittedFrom (inclusive) and submittedBefore
(exclusive), both applied to submittedAt. If both are supplied, from must precede
before. Malformed dates and invalid ranges return 400. For example:

    GET /api/users/me/submissions?assignmentId=<uuid>&status=COMPLETED&submittedFrom=2026-11-01T00:00:00Z&submittedBefore=2026-12-01T00:00:00Z&page=0&size=20&sort=submittedAt,desc

Page size is bounded to 100. Ownership, path assignment, query assignment, and date
filters are intersected. No filter widens access. See [Submissions](submissions.md)
for the sorting whitelist and response pagination fields.

## Ranking

The leaderboard returns content, page, size, totalElements, and totalPages. Each
entry contains exactly rank, username, bestScore, and submissionId. Default page
size is 20, maximum 100; ranking order is fixed. Ranks are one-based and remain
global across pages. Empty/out-of-range pages have empty content.

Eligible entries require COMPLETED status, a completion timestamp, a summary
matching the revision's maximum and test count, and internally consistent stored
test outcomes. Pending, running, failed, cancelled, missing/incomplete/inconsistent
legacy reports, and INTERNAL_ERROR evaluations are excluded. Valid compilation
failures have zero score and no test outcomes and are eligible. Legacy complete
results remain eligible when they meet the same consistency checks.

For each submitter, PostgreSQL chooses the highest raw total score for the
assignment, then the earliest completedAt, then the smallest submission UUID.
Students are ranked by those same fields, with student UUID as an additional
final tie-breaker. Scores are not normalized or rounded for ranking. Results
across revisions retain their original grading rules; later revisions never
recompute old grades. A submitter's later role/status changes do not erase their
historical score. Username reflects the current account name.

SQL window functions calculate the best submissions and global ranks. Only the
requested page reaches Java; indexes support assignment/completion lookup and
per-submission result joins. Count and page use the same repeatable-read snapshot.
Private result retrieval also uses a repeatable-read snapshot to avoid observing
partial completion.

## Internal evaluation boundary

EvaluationCompletionService is a transactional application service, with no HTTP
controller, Redis consumer, scheduled evaluator, or automatic caller. Both begin
and complete require the dedicated EVALUATION_WRITE authority. Local JWT conversion
only grants ROLE_STUDENT, ROLE_TEACHER, or ROLE_ADMIN from PostgreSQL, so none of
those users (including administrators) can call this boundary via local login.
Token scope/authority claims do not grant evaluator authority.

A future worker transport must authenticate an evaluator service identity,
validate audience/issuer and evaluator scope (or use authenticated mTLS), and map
only that verified identity to EVALUATION_WRITE. Do not expose these methods as a
public grade-submission endpoint or trust authority fields in queue messages.
Transport implementation, credentials, attempt leases, retry scheduling, and
worker execution are deferred; this stage provisions no evaluator credentials.

The internal sequence is:

1. Call begin(submissionId, attemptId, expectedVersion). A fresh submission starts
   at version 0. The server records RUNNING, startedAt, and increments attemptCount.
   It returns submissionId, attemptId, revisionId, and the new version.
2. A retry of the current attempt ID replays that identity without incrementing
   the count. Replacing a running attempt requires its current expectedVersion
   and a fresh globally unique attempt UUID. Only trusted orchestration should
   authorize replacement; there is no lease-expiry automation yet.
3. Supply EvaluationCompletion with submissionId, attemptId, verdict, totalScore,
   passedTests, totalTests, optional executionTimeMs, and the actual test outcomes.
   The server verifies IDs against the sealed revision, counts, score precision
   and bounds, totals, verdict consistency, and nonnegative timings. An ACCEPTED
   test earns its configured points; other statuses earn zero. Normal reports
   contain every configured test. Compilation failures contain no executed tests;
   internal failures can contain partial outcomes and are excluded from ranking.
   The API stores only supplied outcomes. It does not invent missing outcomes.
4. Summary, individual outcomes, completion fingerprint, COMPLETED status, and
   server-side completedAt commit in one transaction. maximumScore comes from the
   immutable grading revision, not an evaluator-controlled override.

Actual output is optional and bounded to 64 KiB UTF-8 per test, with NUL rejected
because PostgreSQL text cannot store it. A future adapter must bound the total
message size and authenticated transport before materializing the report.

The submission row lock serializes begin/completion calls, and @Version plus the
expectedVersion guard prevents stale replacements. Reports from replaced attempts
are rejected. Equivalent completion retries (including different test ordering
or decimal scale) leave timestamps, version, and rows unchanged. Different reports
for an already completed attempt are conflicts. Completed evaluations cannot be
reopened through this service. PostgreSQL guards prevent mutation of results
completed through this boundary. Legacy rows remain readable without fabricating
attempt IDs or fingerprints.

Flyway V9 adds version, evaluation_attempt_id, evaluation_fingerprint, completion
immutability guards, and a leaderboard index. Earlier migrations remain unchanged.
It does not create any evaluation records. No new runtime environment settings
are required for Stage 9.

## Verification

Run from the root with Java 25, Maven, and Docker running:

    mvn clean verify
    mvn clean verify -Pintegration-tests

EvaluationManagementIT uses real PostgreSQL and HTTP/JWT authentication. Synthetic
reports exist only inside tests, where they exercise visibility, historical
snapshots, dates, pagination, ranking ties, duplicate/stale attempts, concurrency,
and immutable completion. Existing submission/storage tests retain MinIO coverage.

The [worker transport](worker-transport.md) now supplies source/sealed tests and
persists evaluations from the Docker worker. Public source-download and rejudge
endpoints remain outside the current API.

