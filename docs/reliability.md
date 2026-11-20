# Delivery and recovery

## Guarantees and failure windows

Submission acceptance persists a private source before committing submission and
outbox metadata in one PostgreSQL transaction. HTTP 201 follows commit. Redis is
not involved in acceptance. Idempotency keys and durable object-cleanup intents
handle ambiguous HTTP, S3, and database outcomes; see [submissions](submissions.md).

The publisher selects one due unpublished event FOR UPDATE SKIP LOCKED. It holds
that row and one database connection across the bounded Redis call, then marks it
published after XADD acknowledges. A batch defaults to 25, each row in its own
transaction. Locks automatically release on process/database failure. Independent
API instances skip each other's locked rows. No distributed transaction exists.

| Failure | Outcome |
| --- | --- |
| Redis unavailable before acknowledgement | Keep event unpublished, increment attempt, schedule backoff |
| Redis accepted but acknowledgement lost | Retry may duplicate the stable event ID |
| Redis accepted, PostgreSQL update/commit failed | Row remains unpublished; restart/retry duplicates are safe for a deduplicating worker |
| API crashes before publication | Durable event remains due after restart |
| Unknown event type/version or malformed IDs | Retain with INVALID_EVENT and capped backoff; alert and fix, never silently discard |
| MinIO/database fails during acceptance | No acknowledged submission; same-key retry and durable cleanup reconcile partial writes |
| Redis persistence lost or stream trimmed | Periodic reconciliation republishes unfinished events; consumer groups are recreated |

Backoff starts at 1 s and doubles to a 5 minute cap; retries are indefinite, not
a finite silent-drop policy. attempt_count counts committed delivery attempts.
last_error_code is a fixed sanitized code. A database/commit failure is reported
in metrics/logs and retried in a subsequent poll. The transaction timeout is 10 s;
keep Redis connection/command timeouts substantially below it (defaults 2 s each).
No business state changes merely because an event was sent.

## Retention and replay

XADD uses exact MAXLEN (default 100000). Growth is bounded but old entries can be
trimmed even if a future consumer has not processed them. PostgreSQL outbox rows
are therefore retained indefinitely in this stage, including published rows.
They are the recovery source. Monitor backlog, oldest age, XLEN, database disk,
and future consumer lag. Do not purge PostgreSQL delivery history until a tested
archive and replay policy exists. Exact trimming has a cost: size the cap and
benchmark it against the expected workload; arbitrary large caps are not a fix.

For Redis loss, retention gaps, or initial worker onboarding:

1. Diagnose the outage, restore PostgreSQL/MinIO consistency, and restore Redis.
2. Identify the affected event creation window and pending submissions. Stop or
   coordinate the worker so replay will not create uncontrolled work. Ensure
   durable event/attempt deduplication before consumption.
3. Preview infrastructure/operations/replay-outbox.sql with psql and since/before
   UTC timestamps. It defaults to dry-run and limits each batch to at most 1000.
4. Apply explicitly with apply=true, then let the normal publisher deliver.
   Event IDs and creation timestamps stay unchanged. Repeat bounded windows/batches
   while watching stream capacity and worker progress; replaying everything at once
   into a smaller stream can immediately trim earlier replayed entries.
   Advance -v after_id to the greatest UUID returned by each applied batch
   before selecting the next batch in that same time window; the default cursor
   starts at zero. Otherwise the first batch could be replayed repeatedly.
5. Reconcile pending submissions against the worker's durable inbox and completed
   results. A delivered event does not prove evaluation completion.

Example (PG connection credentials come from a protected operator environment):

    psql -v since='2026-11-01T00:00:00Z' -v before='2026-11-02T00:00:00Z' -v batch_size=100 -f infrastructure/operations/replay-outbox.sql
    psql -v since='2026-11-01T00:00:00Z' -v before='2026-11-02T00:00:00Z' -v batch_size=100 -v apply=true -f infrastructure/operations/replay-outbox.sql

The script targets PENDING submissions and RUNNING submissions whose persisted
evaluation_lease_until has expired. It locks submissions and outbox rows, skips
locked rows and never resets business state. With worker transport enabled, the
API also reconciles up to 100 such events every minute once their publication is
older than five minutes. The interval is configurable through
worker.recovery.interval; worker.recovery.minimum-age defaults to five minutes.
Publication uses the original event ID. This can
duplicate an intact but slow delivery; begin/complete deduplication makes it safe.
Live attempts renew their API lease and Redis idle time. Renewal is fenced by
attempt ID; queue renewal/dead-letter operations also check consumer ownership.
Pending scans use a cursor so the oldest 100 records cannot starve later records.
Rejected result reports attempt a minimal INTERNAL_ERROR completion before DLQ.
Permanently invalid events and a missing/corrupt source still require operator
investigation; reconciliation cannot reconstruct a lost source file.
A complete database loss requires backup restore; Redis is not a database backup.

## Durability and observability

Compose uses named volumes, Redis AOF with appendfsync always and noeviction.
AOF always reduces the local acknowledgement-to-disk window but does not provide
replication, fault-tolerant disks, or guaranteed zero loss. Production needs a
chosen persistence/replication/failover policy, off-host backups, restore tests,
TLS/ACLs, and capacity alerts. PostgreSQL and MinIO also need persistent storage
and backup/restore policies, with source and metadata recovered consistently.

Readiness requires db and s3; Redis outages deliberately leave the durable
acceptance path ready. /actuator/health/dependencies reports db/redis/s3 collectively.
Liveness checks the process lifecycle, so a dependency outage does not cause a
restart storm. S3 health is an authenticated HEAD connectivity check, not proof
that writes/disk capacity are healthy. Failed PUTs are surfaced during acceptance.

ADMIN-protected Prometheus metrics include judge_submissions_accepted/pending/failed,
judge_outbox_backlog, judge_outbox_oldest_seconds, judge_outbox_publishing_failures_total,
judge_redis_stream_length, judge_metrics_snapshot_timestamp, and
judge_submission_acceptance_seconds histograms by outcome. Database gauges refresh
every 30 s and are global: do not sum them across API replicas. Failed snapshots
return NaN and retain the last successful snapshot timestamp. These exact-count
queries should be profiled as data grows. Delivery counters are process-local;
a crash/rollback can differ from persisted attempt counts. Standard JVM/process,
HTTP, pool, and CPU/memory meters are available. Do not add user IDs, source keys,
or tokens as metric labels.

ECS JSON delivery logs contain stable event IDs and outcome/reason codes only.
Bootstrap passwords, JWTs, source, arbitrary payloads, and test contents must not
be logged. See [security](security.md) and [SLO targets](slo-sla.md).

