# Architecture

The Maven reactor contains the root parent, modules/services aggregators,
judge-common, judge-messaging, judge-storage, judge-api, and the intentionally empty
judge-worker JAR. Java 25 and Spring Boot 4.1.2 are used consistently. Features live
inside judge-api with DTO/controller, application, domain, and persistence layers.
JPA entities stay in the API. judge-messaging contains Java-only contracts and the
publisher interface; its Spring Data Redis adapter lives in judge-api.

## Submission and delivery

1. JWT authentication resolves the current enabled user from PostgreSQL. Only a
   STUDENT can submit to a published assignment before its post-upload deadline.
2. Source is validated and staged on disk, with a generated key and SHA-256.
   A durable idempotency reservation and cleanup intent precede the MinIO PUT.
3. After the private object is stored, the API rechecks identity, publication,
   revision, and deadline. Submission metadata and SUBMISSION_CREATED outbox
   data commit together. HTTP 201 follows commit. No Redis call delays acceptance.
4. A scheduled publisher locks one due event using FOR UPDATE SKIP LOCKED, sends
   its identifier-only message with XADD, then records publication. Each row uses
   its own short transaction; multiple instances can work independently.
5. Redis and PostgreSQL do not share a transaction. Lost acknowledgements/commits
   can cause duplicate deliveries. eventId is stable; the Redis record ID is not.
6. No consumer exists yet. Submissions stay PENDING, result grades stay null,
   and leaderboards stay empty until a trusted evaluator supplies real results.

PostgreSQL is authoritative for identity, ownership, immutable grading revisions,
submissions, results, and recoverable delivery intent. Private MinIO objects hold
source. Redis is a bounded delivery buffer, not the system of record. Durable
outbox history supports controlled replay after trimming or Redis recovery.

## Boundaries

Authentication is separate from assignment/submission business services. Students
can read only their private submissions; teachers can read their assignments;
admins have authorized platform access. Hidden grading data is redacted from
student result responses. EvaluationCompletionService has a distinct internal
EVALUATION_WRITE boundary unavailable through local user JWTs.

No compilation, extraction, execution, simulated results, worker deployment, or
worker consumer is implemented. See [worker integration](worker-integration.md),
[database design](database-design.md), and [reliability](reliability.md).

