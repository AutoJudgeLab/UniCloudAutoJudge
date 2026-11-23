# Stage 10 verification record

Local verification on 2026-11-13 used Java 25, Maven, and an ARM64 Docker host.
This records functional checks and a tiny instrumentation smoke run, not a
production capacity certification or an SLA result.

## Build and functional tests

- mvn clean verify: successful eight-module reactor.
- mvn clean verify -Pintegration-tests: 129 tests, zero failures/errors/skips.
- Seven new OutboxIntegrationIT tests use actual PostgreSQL, authenticated Redis,
  and MinIO. They cover the full user workflow, Redis pause/unavailability,
  restart recovery, duplicate stable event IDs after a database failure,
  two publisher instances, concurrent idempotent requests, bounded retention and
  replay, malformed-event retries, immutable event identity, health and metrics.
- Existing suites verify schema upgrades, authentication/bootstrap, administration,
  assignment/test ownership and revisions, source validation/storage failure,
  submission reliability, results, leaderboards, and evaluation completion.
- The production API image built as arm64 and runs with USER 10001:10001.
- The isolated Compose workflow passed health, Swagger, registration, admin teacher
  promotion, assignment/test publication, source upload, private object existence,
  PostgreSQL submission/outbox, Redis delivery, PENDING/history, and access denials.
- The operator replay SQL passed preview and apply against that isolated stack.
- The stack and its generated credentials/volumes were removed after testing.
- kubectl kustomize renders all basic manifests. No live Kubernetes deployment,
  ingress, database restore, cross-node failover, or AMD64 image run was performed.
- judge-worker's ordinary JAR contains metadata/.gitkeep only, no Java classes.

## Load-tool smoke sample

The optional Compose smoke ran k6 with two virtual users and four total requests,
a single student/assignment, and a 14-byte Java source file. The API container was
limited to two CPUs and 1 GiB. Raw local output is in .local/stage10-load (ignored
by Git, contains no credentials); larger experiment data should be archived by
the operator with its environment description.

| Measurement | Observed smoke sample |
| --- | --- |
| Successful HTTP 201 requests | 4/4 (100%) |
| HTTP error rate | 0% |
| Client acceptance p50 | 25 ms |
| Client acceptance p95 / p99 | 31 ms / 31 ms |
| Sampled outbox backlog | 0 at each collected snapshot |
| Sampled stream length | 1 to 6, including workflow/replay and load events |
| Sampled container CPU | 0.79% to 3.00% (Docker's reported CPU percentage) |
| Sampled container memory | 351.2–352.9 MiB |

Only five resource snapshots were collected. Brief backlog/resource peaks can be
missed; percentiles from four requests are statistically insufficient for an SLO.
This verifies collection of the requested signals, not supported concurrency,
sustained throughput, tail latency under a burst, or reliability under disk loss.
Use the [load procedure](deployment.md) for representative experiments and the
[proposed SLO definitions](slo-sla.md) before making operational promises.

