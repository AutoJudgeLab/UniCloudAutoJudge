# Assignment test-case management

Stage 6 adds the `testcase/api`, `testcase/application`, `testcase/domain`, and
`testcase/persistence` layers inside judge-api. Test cases belong to numbered
assignment revisions. No grading, compilation, or worker functionality is implemented.

## Endpoints and authorization

All routes require `Authorization: Bearer <accessToken>`.

| Method and route | Success | Behavior |
| --- | --- | --- |
| `POST /api/assignments/{assignmentId}/test-cases` | 201 | Add a test to the draft; Location identifies the created test. |
| `GET /api/assignments/{assignmentId}/test-cases` | 200 | JSON array of current visible tests in execution order. |
| `GET /api/assignments/{assignmentId}/test-cases/{testCaseId}` | 200 | Get a visible test in the current revision. |
| `PUT /api/assignments/{assignmentId}/test-cases/{testCaseId}` | 200 | Replace a test's editable fields. |
| `DELETE /api/assignments/{assignmentId}/test-cases/{testCaseId}` | 204 | Remove the test from the draft, retaining sealed history. |

TEACHER can read and manage tests on their own assignments. ADMIN can read and
manage tests on any assignment. STUDENT can read only public tests from a
PUBLISHED assignment's current sealed revision. Hidden tests are filtered in SQL
before payloads are loaded for students. Individual hidden test requests return
404, as do requests for tests from another assignment or an old revision. Students
cannot access draft or archived test cases or perform any mutations.

Read transactions hold a shared assignment lock so visibility checks and test
selection stay consistent during concurrent publication, archiving, and revision
creation. Mutations check the current actor's enabled state, token version, role,
and ownership inside the transaction, then lock the assignment and grading revisions.

## Request and response

POST and PUT use the same body:

```json
{
  "inputData": "2 3\n",
  "expectedOutput": "5\n",
  "points": 100,
  "isHidden": false,
  "executionOrder": 0
}
```

- Input and expected output are required strings; empty strings are valid. Their
  whitespace and line endings are preserved.
- Points must be positive, with at most 15 integer digits and 4 fractional digits.
  Scoring uses BigDecimal and exact decimal comparison.
- `isHidden` defaults to true when omitted or null.
- Execution order is required, zero-based, and nonnegative. Gaps are allowed.
  Orders are unique within the assignment's current revision. Historical revisions
  reuse order numbers without conflicting with the current configuration.
- Unknown fields, including `id` and `assignmentId`, are rejected. Ownership and
  assignment membership are resolved from authenticated identity and the URL.

Responses contain exactly `id`, `assignmentId`, `inputData`, `expectedOutput`,
`points`, `isHidden`, and `executionOrder`. Every successful response includes
`X-Assignment-Revision`; legacy empty assignments without a revision report 0.
There is no hidden-case count or hidden payload in student list responses.

Example, after creating a DRAFT assignment with maximumScore 100:

```sh
curl -i 'http://localhost:8080/api/assignments/<assignmentId>/test-cases' \
  -H 'Authorization: Bearer <teacherAccessToken>' \
  -H 'Content-Type: application/json' \
  -d '{"inputData":"2 3\n","expectedOutput":"5\n","points":100,"isHidden":false,"executionOrder":0}'

curl -X POST 'http://localhost:8080/api/assignments/<assignmentId>/publish' \
  -H 'Authorization: Bearer <teacherAccessToken>'
```

## Publication and immutable history

Publication now requires valid assignment metadata and a future deadline, at least
one test, positive points for every test, and an exact sum equal to maximumScore.
The API seals the revision before setting PUBLISHED, in one transaction. Failed
validation leaves the draft unchanged. Repeated publication of an already
published sealed revision is idempotent. Both public and hidden tests contribute
to the score; an assignment may contain only hidden tests.

Draft test cases are editable in place. A test-case POST, PUT, or DELETE on a
published assignment or sealed revision automatically copies the current metadata
and all tests to a new numbered draft revision, then applies the change to that
copy. No query flag is required. The assignment becomes DRAFT and must pass
publication validation again. Archived assignments are read-only.

Every copied test receives a new ID. PUT returns the ID of the edited copy; DELETE
returns the new revision header. Reload the list whenever the revision changes.
Old IDs return 404 through these current-configuration endpoints, including for
administrators; they remain stored for historical submissions/results. Concurrent
requests using IDs from a revision that has just been replaced must reload and retry.

The original revision and its tests cannot be edited or deleted, even if there
are no submissions yet. Every existing submission remains linked to its original
sealed revision and test-result IDs. Copies preserve input, expected output,
points, visibility, and order until explicitly edited. Point weights are never
automatically rescaled. Changing a test or assignment maximum may leave a draft
with an invalid total; correct it before publishing.

## Migration and errors

V1–V6 remain unchanged. V7 adds a positive-points guard when sealing, a partial
index for public test reads, and a compatibility update: Stage 5 publications
without matching, valid, sealed grading return to DRAFT for review. Valid sealed
publications stay published. No historical revision, test, submission, or result
is rewritten or removed.

Earlier schemas allowed zero-point cases. They remain readable as stored history
and can be copied into drafts for explicit correction. API writes require positive
points, and V7 prevents sealing a new revision containing zero-point cases. The
original entity/database nonnegative constraint remains to represent those legacy
records safely.

Errors use `application/problem+json` without echoing test payloads:

| Status | Codes |
| --- | --- |
| 400 | `VALIDATION_FAILED`, `INVALID_REQUEST`, `INVALID_TEST_CASE`, `INVALID_ASSIGNMENT` |
| 401 | `UNAUTHORIZED` |
| 403 | `FORBIDDEN` |
| 404 | `ASSIGNMENT_NOT_FOUND`, `TEST_CASE_NOT_FOUND` |
| 409 | `DUPLICATE_EXECUTION_ORDER`, `ARCHIVED_ASSIGNMENT`, `TEST_CASE_CONFLICT`, `CONCURRENT_UPDATE` |
| 409 at publication | `TEST_CASES_REQUIRED`, `INVALID_TEST_CASES`, `TEST_CASE_SCORE_MISMATCH` |

## Verification

```sh
mvn clean verify
mvn clean verify -Pintegration-tests
```

The first command runs Docker-free unit tests. The second also runs PostgreSQL
Testcontainers tests. `AssignmentTestCasesIT` covers CRUD, empty strings, positive
scores, duplicate order, ownership, admin access, hidden visibility, publication
validation, immutable historical results, concurrent requests, OpenAPI, and the
Stage 5 upgrade. Existing assignment tests now include valid tests before publishing.

Swagger UI exposes the Test cases tag at `/swagger-ui/index.html`. No new
environment variables are required for test-case management. Stage 9 adds
[result APIs and leaderboards](evaluation-results.md). Automatic grading, worker
execution remain deferred; Stage 10 implements Redis publishing. Stage 8
[submissions](submissions.md) use the [storage service](storage.md) and sealed tests.
