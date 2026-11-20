# Worker integration

judge-worker consumes Redis events, obtains source and sealed tests through authenticated internal
HTTP routes, executes code in Docker and stores the result through the API. See the implemented
[worker transport](worker-transport.md) and [language toolchains](../services/judge-worker/README.md).
The ordinary JAR is usable in integration tests; the `-exec.jar` is the runnable worker application.

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

## Processing and operational requirements

- Enable the internal HTTP transport with a separate opaque service credential granting only
  `EVALUATION_WRITE`. The API validates the event/submission/revision relationship. Events are not
  download grants. Workers must not receive database or MinIO credentials or reuse student JWTs.
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
  limits and artifact/output bounds. Execution is implemented in judge-worker, never judge-api.
  Review residual sandbox risks and target-host isolation before public deployment.
- Coordinate consumer lag/PEL recovery with bounded stream retention. Replay from
  retained PostgreSQL outbox rows is required after trimming or Redis loss. Initial
  consumer onboarding must also recover old pending submissions whose entries
  were trimmed before a consumer existed.

See [evaluation completion](evaluation-results.md) and [reliability](reliability.md).
No fake evaluations or startup result seeders are included.

