# Assignment management

Stages 5–6 provide metadata, test-case, and lifecycle management inside `judge-api`. The feature
uses `assignment/api` for validated DTOs and OpenAPI controllers,
`assignment/application` for transactions and authorization, `assignment/domain`
for entities/status/errors, and `assignment/persistence` for repositories.
JPA entities never appear in API responses.

## Access and endpoints

All routes require `Authorization: Bearer <accessToken>`. A TEACHER can create
assignments and read/manage only their own assignments. ADMIN can read/manage
all assignments and create assignments owned by their own authenticated account.
STUDENT can list/read PUBLISHED assignments only. Ownership always comes from
`CurrentUser`, never a request-provided ID.

| Method and route | Success | Behavior |
| --- | --- | --- |
| `POST /api/assignments` | 201 | Create DRAFT metadata and revision 1; Location points to the new resource. |
| `GET /api/assignments` | 200 | Paginated assignments within the caller's visibility. |
| `GET /api/assignments/{assignmentId}` | 200 | Return visible assignment metadata. |
| `PUT /api/assignments/{assignmentId}` | 200 | Replace metadata; changed drafts append a revision. |
| `DELETE /api/assignments/{assignmentId}` | 204 | Delete only when unarchived, with no submissions or sealed revisions. |
| `POST /api/assignments/{assignmentId}/publish` | 200 | Validate metadata and test scores, seal grading, and make it visible to students. |
| `POST /api/assignments/{assignmentId}/archive` | 200 | Retain history, make read-only, and remove from student discovery. |

Only TEACHER and ADMIN can mutate assignments. Forbidden mutations return 403;
missing or hidden detail reads return 404. Archived assignments remain readable
by the owning teacher and administrators. Future historical-result endpoints must
authorize students against their own submissions and use their original revisions.

## Requests and responses

Create and PUT accept exactly these fields:

```json
{
  "title": "Integer sum",
  "description": "Read two integers and print their sum.",
  "maximumScore": 100,
  "deadline": "2030-06-30T20:59:00Z"
}
```

Use a deadline in the future. Title is stripped, required, and at most 255
characters. Description must be nonblank. Maximum score must be positive with
at most 15 integer digits and 4 fractional digits; it is stored as BigDecimal.
Deadline must be an ISO-8601 timestamp with an offset and is stored as UTC.
Unknown fields, including `teacherId` and `status`, are rejected with 400.

Responses contain exactly `id`, `title`, `description`, `maximumScore`, `deadline`,
`status`, `teacherId`, `createdAt`, and `updatedAt`. No user security data or test
input/output is returned. Create, detail, PUT, publish, and archive responses also
include `X-Assignment-Revision`, the current numbered metadata revision. Legacy
assignments without a revision report 0 until a revision is materialized by editing
or publishing. This header identifies a revision; it is not an optimistic lock token.

Examples after obtaining a teacher token (substitute your token and UUID):

```sh
curl -i http://localhost:8080/api/assignments \
  -H 'Authorization: Bearer <accessToken>' \
  -H 'Content-Type: application/json' \
  -d '{"title":"Integer sum","description":"Print the sum of two integers.","maximumScore":100,"deadline":"2030-06-30T20:59:00Z"}'

# Add tests through the test-case endpoints before publishing.
curl -X POST 'http://localhost:8080/api/assignments/<assignmentId>/publish' \
  -H 'Authorization: Bearer <accessToken>'
```

## Pagination and sorting

`GET /api/assignments?page=0&size=20&sort=createdAt,desc&status=PUBLISHED`

- `page`: zero-based, at least 0; defaults to 0.
- `size`: 1–100; defaults to 20.
- `sort`: one `field,asc` or `field,desc`; defaults to `createdAt,desc`. Allowed
  fields: `id`, `title`, `maximumScore`, `deadline`, `status`, `createdAt`, `updatedAt`.
  ID ascending breaks ties unless sorting by ID itself.
- `status`: optional DRAFT, PUBLISHED, or ARCHIVED, intersected with role visibility.
  A student filtering DRAFT or ARCHIVED receives an empty page.

The response contains `content`, `page`, `size`, `totalElements`, and `totalPages`.
Invalid UUIDs, filters, sort values, or pagination return 400 Problem Details.

## Publication, revisions, and retention

Creation starts in DRAFT. Publish validates required metadata, positive maximum
score, and a future deadline. Stage 6 additionally requires at least one valid
test case, positive points for every case, and an exact sum matching maximumScore.
Publication seals the grading revision in the same transaction. Repeated publication
is idempotent. See [Test-case management](test-cases.md) to define tests before publishing.
An empty or invalid grading configuration cannot be published.

Every changed draft metadata update appends a numbered snapshot. An identical
PUT is a no-op. Editing published metadata or a sealed configuration requires:

```text
PUT /api/assignments/{assignmentId}?createNewRevision=true
```

Use the same four-field payload. The flag explicitly creates a new revision even
with unchanged metadata. The assignment returns to DRAFT for review and
republishing. The previous revision's tests are copied with new IDs, preserving
their input, output, points, visibility, and order. Changing maximum score never
silently rescales test points. The new revision stays unsealed; use the test-case
endpoints to reconcile points, then publish to seal it. The submission service
checks PUBLISHED status and uses the newest revision only if it is sealed.

Existing submissions/results retain their exact original revision and test IDs.
V6 prevents in-place metadata edits to any revision, including unsealed revisions.
Sealed test cases remain immutable under V3. Prior Flyway files are unchanged.
Upgrading a database with zero-score or blank-description assignment rows requires
explicit correction before V6 can apply; the migration does not silently rewrite data.

Writes lock the actor's user row and assignment row, then revision rows. The actor's
current role, enabled state, and authentication version are rechecked inside the
transaction. Assignment row locking serializes concurrent revision allocation and
protects dependency checks; restrictive foreign keys remain the final deletion
guard. Concurrent explicit edits each retain their own numbered revision; the last
committed edit becomes current. A retryable database conflict returns 409.

Archive is allowed after the deadline and is idempotent. Archived assignments are
read-only and cannot be physically deleted or republished. Deleting another
assignment fails if any submission or sealed revision exists. Unsealed test cases
and revisions are explicitly removed in dependency order when deletion is allowed.
There is no cascade deletion of historical submissions or results.

Test-case edits to a published or sealed revision automatically create a new draft
without requiring the metadata endpoint's query flag. Tests receive new IDs when
copied. Reload the current test list after a revision changes. V7 returns legacy
metadata-only publications to DRAFT without changing their submissions or revisions.

Domain conflict codes (409) are `REVISION_REQUIRED`, `ARCHIVED_ASSIGNMENT`,
`ASSIGNMENT_HAS_SUBMISSIONS`, `GRADING_HISTORY_RETAINED`, and `ASSIGNMENT_IN_USE`.
Missing/hidden assignments use `ASSIGNMENT_NOT_FOUND` (404), invalid publication
metadata uses `INVALID_ASSIGNMENT` (400). Publication grading conflicts use
`TEST_CASES_REQUIRED`, `INVALID_TEST_CASES`, or `TEST_CASE_SCORE_MISMATCH` (409). DTO validation uses
`VALIDATION_FAILED` (400). Errors use `application/problem+json`.

## Verification and remaining stages

```sh
mvn clean verify
mvn clean verify -Pintegration-tests
```

The full suite requires Docker and starts isolated PostgreSQL containers.
`AssignmentManagementIT` exercises creation, updates, publication, archiving,
deadline/score validation, every role/ownership boundary, safe DTOs, sorting and
pagination, explicit revisions, unchanged historical results, deletion safeguards,
database snapshot immutability, concurrent revisions, stale identities, and OpenAPI.

Swagger UI is at `/swagger-ui/index.html`, with an Assignments tag and bearer
authentication. [Stage 8 submissions](submissions.md) use these grading revisions.
[Stage 9](evaluation-results.md) adds result APIs and leaderboards. Stage 10 implements Redis publishing; worker behavior remains deferred.
