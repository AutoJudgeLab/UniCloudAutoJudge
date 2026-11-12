# Proposed SLOs and SLA status

These are initial engineering targets requiring representative load tests,
monitoring, operational staffing, and recovery exercises. They are not measured
capacity claims or a contractual SLA. No SLA or service credits are offered here.

| Indicator | Proposed target |
| --- | --- |
| API availability | 99.5% per calendar month |
| Submission acceptance latency | p95 <= 500 ms |
| Burst submission acceptance latency | p99 <= 2 seconds in an agreed burst window |
| Acknowledged submission loss | Zero as a reliability objective |

Availability is successful service of eligible API requests at the external
boundary, counting server 5xx, timeouts, and unreachable service as bad events.
Invalid credentials, malformed requests, ownership denials, and expired-deadline
requests are excluded; overload-induced failures of valid requests count as bad.
Synthetic probes must capture periods with no traffic. Define exclusions and the
burst arrival profile before judging results; do not hide maintenance outages by
changing the denominator afterward.

## Acceptance timing

judge.submission.acceptance is a servlet-entry-to-filter-return timer, spanning
JWT validation, multipart parsing/staging, MinIO PUT, database/outbox commit, and
response preparation. It has fixed outcome labels accepted/rejected/error and
histogram boundaries including 500 ms and 2 s. Accepted counts include successful
idempotent replays. It excludes time before the server receives dispatch and the
final network delivery of response bytes. A 201 is emitted only after source and
database durability checks succeed. Client k6 acceptance_latency_ms measures
request-start to full response, including transmission. Use both signals; the
external target is client-observed completion for valid accepted requests at the
documented file size and network conditions. Report error rates alongside latency
so dropped requests cannot make percentiles appear better.

The k6 script reports p50/p95/p99, success rate, and HTTP error rate. The metrics
collector samples outbox backlog/oldest age, Redis stream length, JVM/process
CPU/memory, and optionally Docker CPU/memory. Define steady and burst windows,
file-size mix, student count, database size, and infrastructure before testing.
A four-request smoke test validates instrumentation only, not these targets.

## Error budget and response

99.5% monthly availability permits 0.5% bad eligible requests. A time-based
30-day illustration is 216 minutes (3 hours 36 minutes); 31 days is 223.2 minutes.
Use the actual calendar month's denominator and do not conflate request-weighted
and time-based budgets. p95 allows at most 5% of accepted measurements above
500 ms; p99 permits at most 1% above 2 s in the agreed burst population. Track
these latency budgets separately.

Alert on fast and sustained error-budget consumption, oldest outbox age, growing
backlog, stream saturation, stale metric snapshots, database/storage capacity,
and any acknowledged-source/metadata inconsistency. When the availability budget
is exhausted, pause discretionary releases and capacity increases, investigate,
restore service/backlogs, and prioritize reliability fixes. Resume feature rollout
only after the incident review and evidence that budget burn is controlled.

Zero acknowledged loss means retained source plus submission/revision metadata
must survive supported failures. It requires durable storage, PostgreSQL backups
and PITR, protected MinIO persistence, tested restore procedures, outbox retention,
and operational replay. A single-node cluster, unreplicated disks, or untested
backups cannot establish this objective. Redis loss must be recovered from the
outbox; publication itself does not satisfy the source/data reliability objective.

