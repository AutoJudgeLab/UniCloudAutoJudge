# API reference

Interactive OpenAPI: /swagger-ui/index.html and /v3/api-docs when OPENAPI_ENABLED=true.
Set it false for deployments that should not expose documentation. Business routes
require Bearer JWTs except public registration/login and the minimal health route.
DTOs and centralized ProblemDetail responses are used throughout.

| Routes | Access |
| --- | --- |
| POST /api/auth/register | Public; always creates STUDENT, rejects supplied roles |
| POST /api/auth/login | Public; username/email identifier and password |
| GET /api/users/me | Authenticated current identity |
| GET /api/admin/users, GET /api/admin/users/{userId} | ADMIN |
| PATCH /api/admin/users/{userId}/role, /status | ADMIN; role/status and last-admin protections |
| POST /api/assignments | TEACHER or ADMIN |
| GET /api/assignments, /api/assignments/{assignmentId} | Visibility scoped to role/publication |
| PUT, DELETE /api/assignments/{assignmentId} | Owning TEACHER or ADMIN |
| POST /api/assignments/{assignmentId}/publish, /archive | Owning TEACHER or ADMIN |
| POST, GET /api/assignments/{assignmentId}/test-cases | Owners/admins manage; students see published public tests |
| GET, PUT, DELETE /api/assignments/{assignmentId}/test-cases/{testCaseId} | Same ownership/visibility boundary |
| POST /api/assignments/{assignmentId}/submissions | STUDENT; multipart file + programmingLanguage + Idempotency-Key |
| GET /api/submissions/{submissionId}, /result | Owning student, assignment teacher, or ADMIN |
| GET /api/users/me/submissions | Current user's history |
| GET /api/assignments/{assignmentId}/submissions | Scoped submission history |
| GET /api/assignments/{assignmentId}/leaderboard | Published leaderboard; authorized teacher/admin historical access |
| GET /api/v1/health | Minimal API availability |
| GET /actuator/health, /liveness, /readiness, /dependencies | Public status only; full paths use /actuator/health prefix |
| GET /actuator/metrics, /actuator/metrics/{name}, /actuator/prometheus | ADMIN only |

Acceptance returns 201 with submissionId, assignmentId, PENDING status, and
server submittedAt. Equivalent retries return the original response; conflicting
key reuse returns 409. Unavailable results are null, never synthetic grades.
No public result-writing, event-replay, worker-claim, or source-download routes
exist. Publishing an event does not change submission status.

History supports page/size, whitelisted sorting, status, assignmentId,
programmingLanguage, submittedFrom (inclusive), and submittedBefore (exclusive).
Leaderboards use database ranking and bounded pagination. See [submissions](submissions.md),
[evaluation results](evaluation-results.md), [authentication](authentication.md),
[administration](user-administration.md), [assignments](assignments.md), and
[test cases](test-cases.md) for request examples and detailed rules.

