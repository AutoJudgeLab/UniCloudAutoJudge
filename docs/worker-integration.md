# Future worker integration

judge-worker remains an ordinary Maven JAR with only its POM and empty source
folders preserved by .gitkeep. There are no classes, Boot entry points, consumers,
execution logic, Docker images, or Kubernetes resources for it.

## Redis wire contract

judge-messaging defines SubmissionCreatedMessageV1 and SubmissionEventPublisher.
The Spring Data Redis implementation lives in judge-api. XADD stores these fields
as strings in REDIS_SUBMISSION_STREAM (default judge:submissions:v1):

    {
      "eventId": "uuid",
      "eventType": "SUBMISSION_CREATED",
      "eventVersion": "1",
      "submissionId": "uuid",
      "assignmentId": "uuid",
      "assignmentRevisionId": "uuid",
      "createdAt": "ISO-8601 UTC timestamp"
    }

The corresponding JSON representation may use numeric eventVersion=1; Redis
fields are strings. createdAt is the immutable outbox creation timestamp, not
Redis receipt time. eventId is the database outbox UUID and remains stable across
retries and replays. Redis's generated stream record ID changes on each delivery.
No source bytes, original filenames, passwords, JWTs, hidden grading data,
MinIO keys/credentials, or arbitrary JSON payload fields are sent.

## Required before implementing a consumer

- Authenticate the worker service and authorize its job/source/revision access.
  There is currently no worker HTTP transport. Design scoped service identities,
  issuer/audience validation or mTLS, and auditing before creating one. An event
  contains no download grant. Workers must not receive unrestricted database or
  MinIO-root credentials or reuse student JWTs.
- Use Redis consumer groups, explicitly chosen start offsets, pending-entry
  recovery/claiming, and acknowledgements only after durable processing state.
- Deduplicate durably by eventId and protect submission/attempt state; consumers
  must tolerate duplicates, replay, and reordered delivery. Do not infer grading
  success from Redis ACK or the outbox published_at timestamp.
- Integrate through the existing EvaluationCompletionService secure boundary.
  Only a verified adapter may grant EVALUATION_WRITE. Local user JWT roles cannot
  grant it. Use fresh attempt IDs and expected versions; never overwrite completed
  evaluations or accept reports from superseded attempts.
- Retrieve the exact immutable assignment revision, not the current editable
  tests. Keep hidden tests and source isolated from other users.
- Run untrusted code in a separately designed sandbox with CPU/memory/time/network
  limits and artifact/output bounds. None of that isolation/execution is supplied
  by judge-api or these manifests.
- Coordinate consumer lag/PEL recovery with bounded stream retention. Replay from
  retained PostgreSQL outbox rows is required after trimming or Redis loss. Initial
  consumer onboarding must also recover old pending submissions whose entries
  were trimmed before a consumer existed.

See [evaluation completion](evaluation-results.md) and [reliability](reliability.md).
No fake evaluations or startup result seeders are included.

