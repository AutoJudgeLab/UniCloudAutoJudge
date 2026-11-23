# Deployment and local verification

## Local development

Install Java 25, Maven, Docker Compose v2, and optionally k6/Python 3 for load tests.
Copy .env.example to .env and supply independent random passwords, bucket-scoped
storage credentials, and matching RSA signing keys as described in
[authentication](authentication.md). Never commit .env or key material.

    docker compose --env-file .env -f infrastructure/docker/compose.yml up -d postgres redis minio
    docker compose --env-file .env -f infrastructure/docker/compose.yml --profile storage-init run --rm minio-init
    mvn clean verify

Export the API variables from your protected local environment and run:

    java -jar services/judge-api/target/judge-api-0.1.0-SNAPSHOT.jar

To run the API in Docker with its dependencies:

    docker compose --env-file .env -f infrastructure/docker/compose.yml --profile api up -d --build --wait

The API profile includes safe bucket initialization and startup dependency checks.
Only selected API settings are passed to the API container; MinIO root credentials
stay in MinIO/storage-init. Host ports bind to 127.0.0.1. PostgreSQL, Redis and MinIO
use persistent named volumes. Normal down retains data:

    docker compose --env-file .env -f infrastructure/docker/compose.yml --profile api down

Adding -v intentionally deletes the selected project's data; do not use it against
real assignments. The isolated smoke script creates its own project, credentials,
ports and volumes, runs the complete workflow, and removes only those resources:

    docker build -f infrastructure/docker/judge-api/Dockerfile -t online-judge-api:0.1.0-SNAPSHOT .
    python3 infrastructure/docker/smoke.py
    RUN_LOAD=true python3 infrastructure/docker/smoke.py

The optional load smoke performs only four requests at two VUs, writes summary
and observations under .local/stage10-load, and establishes no capacity claim.
The smoke verifies initial admin bootstrap, public registration, admin teacher
promotion, teacher assignment/test publication, student upload, private S3 object,
PostgreSQL submission/outbox, Redis delivery, PENDING status/history, authorization,
health and OpenAPI. The regular Testcontainers suite additionally exercises Redis
outage, ambiguous commit, duplicate delivery, and multiple publisher instances.

## Image

The multi-stage Dockerfile builds with Java 25/Maven and runs on a Java 25 JRE as
UID/GID 10001 with no root privileges. Temurin/Maven images support ARM64 and AMD64.
The ARM64 build is exercised locally; validate each deployment architecture and
pin approved base digests in your release process. The source context excludes
.env, keys, Git, and target directories. The runtime uses a read-only filesystem
with bounded writable /tmp, CPU/memory limits, and JVM container memory sizing.
Publish your own immutable application image tag/digest before deploying.

    docker buildx build --platform linux/arm64,linux/amd64 -f infrastructure/docker/judge-api/Dockerfile -t <registry>/judge-api:<release> --push .

This command is an operator example, not an action performed by the implementation.
No worker image is built or deployed.

## Kubernetes/k3s

Basic manifests are in infrastructure/kubernetes: Deployment, ClusterIP Service,
ConfigMap, and explicit Secret references. Render locally with:

    kubectl kustomize infrastructure/kubernetes

Provide persistent PostgreSQL, Redis and private S3-compatible storage first.
Edit service endpoints/TLS settings in the ConfigMap. It uses example in-cluster
names, not provisioned databases. Create judge-api-secrets in the deployment
namespace from a protected operator file/secret manager containing:
postgres-password, redis-password, s3-access-key, s3-secret-key,
jwt-private-key-base64, jwt-public-key-base64. Do not put secrets in Git.

    kubectl create secret generic judge-api-secrets --from-env-file=/secure/judge-api-secrets.env
    kubectl apply -k infrastructure/kubernetes

Replace the local image tag with an accessible immutable image. The deployment
runs non-root with dropped capabilities, no service-account token, read-only root,
bounded temporary storage, requests/limits, startup/liveness/readiness probes,
and graceful termination. Tune initial resource values from measurements; they
are not capacity guarantees. Only the API is specified here. Apply namespace,
ingress/TLS, network policies, monitoring, backups and Pod security controls for
your environment before exposing it. No public ingress is created automatically.

For the first administrator, provision a temporary secret with environment keys
ADMIN_BOOTSTRAP_ENABLED=true, ADMIN_BOOTSTRAP_USERNAME, ADMIN_BOOTSTRAP_EMAIL and
ADMIN_BOOTSTRAP_PASSWORD through a private secret manager, add it temporarily via
envFrom, and roll out. After verifying admin login, remove that reference and
secret and retain ADMIN_BOOTSTRAP_ENABLED=false. Do not place a bootstrap password
in ConfigMap, command arguments, image layers or source control. The secure,
idempotent bootstrap never overwrites an existing admin password on restart.

PostgreSQL, Redis and MinIO must have persistent volumes, capacity monitoring,
backups and tested restore procedures; they are not disposable workloads.
Single-node k3s is a single failure domain and is not highly available, regardless
of pod count. Multiple API replicas improve application availability only when
nodes and backing services also meet the desired failure model. No HA claim is
made by these basic manifests.

## Configuration and operations

.env.example documents Redis host/port/password, optional ACL username/TLS,
connection/command timeouts, stream name/cap, publisher batch/poll/backoff, metrics
interval, and structured log format. Automatic publication defaults on; set
OUTBOX_ENABLED=false only for coordinated maintenance, while monitoring backlog.
Keep source reconciliation enabled. Poll intervals must be positive ISO-8601
scheduled durations; retry delays and batch/stream bounds are validated at startup.

Use /actuator/health/liveness for process probes and /actuator/health/readiness for
acceptance readiness. The latter requires db/s3 and intentionally tolerates Redis
outages. /actuator/health/dependencies reports Redis as well. ADMIN JWTs are
required for /actuator/prometheus and /actuator/metrics. Never expose unrestricted
management endpoints. See [reliability](reliability.md) for recovery and replay.

## Load test procedure

Use a disposable published assignment whose points match its tests and whose
deadline covers the experiment. Provision student/admin tokens via ordinary login;
set BASE_URL, ASSIGNMENT_ID, STUDENT_TOKEN, and ADMIN_TOKEN through a protected
environment. Tokens expire; refresh between experiments or choose a test duration
within their TTL. The script sends unique idempotency keys and valid small Java
source files without executing them.

    mkdir -p .local/load
    python3 infrastructure/load/observe.py --duration 90 --interval 5 --container <api-container-id> > .local/load/observations.jsonl
    VUS=5 DURATION=30s SUMMARY_PATH=.local/load/summary.json k6 run infrastructure/load/submissions.js

Run the collector concurrently in a second terminal; --container is optional.
Collect a baseline before, samples during, and backlog drain after each run.
Vary VUS/duration, student identities, file sizes and burst arrivals deliberately;
the default is a constant-concurrency small-file workload, not a model of all
university traffic. k6 summary reports request success/error rates and p50/p95/p99.
The collector captures backlog/stream growth, process/JVM and container CPU/memory.
Retain configuration, image version, host resources, volume type, sample count,
and raw results. Do not infer supported concurrency from an unmeasured target or
from the four-request smoke test. See [proposed SLOs](slo-sla.md).

