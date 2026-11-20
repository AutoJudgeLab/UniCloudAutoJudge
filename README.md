# University Online Judge

UniCloud Portal integration is available with AUTHENTICATION_MODE=portal.
See [Portal SSO and inter-module calls](docs/portal-sso.md) for OIDC setup,
identity migration, session/CSRF usage and the token-exchange proposal.
Local login remains the default until Portal credentials are configured.

A Java 25 and Spring Boot 4.1.2 platform for programming assignments, submissions,
and future asynchronous evaluation. The project includes the Maven structure, API
startup, health endpoints, local infrastructure, and the Stage 2 database schema,
JPA entities, repositories, immutable grading revisions, JWT authentication,
administrator user management, assignment/test-case management, private object storage,
Stage 8 student submissions with idempotency, an outbox, and source reconciliation,
Stage 9 result retrieval, history filters, and SQL leaderboards, and Stage 10
Redis Streams delivery, observability, container deployment, and recovery tooling.

## Modules

```text
online-judge/                    # Repository root (directory name is optional)
├── pom.xml                     # Parent, dependency and plugin versions
├── services/
│   ├── pom.xml                 # Service aggregator
│   ├── judge-api/              # Executable Spring Boot MVC API
│   └── judge-worker/           # Redis consumer and Docker grader for ten languages
├── modules/
│   ├── pom.xml                 # Shared module aggregator
│   ├── judge-common/           # Future enums, lightweight contracts, utilities
│   ├── judge-messaging/        # Versioned identifier-only submission event contracts
│   └── judge-storage/          # Reusable private S3-compatible source storage
├── infrastructure/
│   ├── docker/                # Compose and MinIO source-build Dockerfile
│   └── kubernetes/             # Reserved for a later stage
└── docs/
```

All eight reactor projects use group ID `org.university.judge` and version
`0.1.0-SNAPSHOT`. Dependency versions come from Spring Boot's parent/BOM;
project-specific overrides live in the root POM. JUnit is pinned to the requested
JUnit 5 line. Both `judge-api` and `judge-worker` enable executable repackaging. judge-common remains
empty. judge-messaging contains a lightweight event contract; judge-storage provides
the MinIO adapter and validated Spring configuration. API packages
are organized by feature.

Spring Framework 7's `SpringExtension` requires JUnit 6. To preserve JUnit 5,
the tests explicitly start and close their Spring contexts instead of using that
extension; integration tests still start the actual Boot application.

## Requirements and verification

- JDK 25, with `JAVA_HOME` pointing to that JDK.
- Maven 3.9 or newer (`java -version` and `mvn -version` should both report Java 25).
- Docker Engine with Docker Compose v2 or newer for local services and integration tests.

From the repository root:

```sh
mvn clean verify
```

This builds all modules and runs JUnit 5 health, password-policy and key tests without Docker or
credentials. The worker unit tests cover its grading and delivery logic. Empty JAR warnings for judge-common are expected.
With Docker running, also verify the full application, Flyway migrations, entity
mappings, database constraints, grading history, PostgreSQL, Redis, Actuator,
OpenAPI, authentication, administrator user management, assignment lifecycle and
ownership, test visibility and publication validation, versioned grading history, token revocation, and
concurrency safeguards using isolated Testcontainers. The storage suite also builds
the pinned MinIO server/client images and verifies private-bucket provisioning,
streamed object operations, content validation, and failures. Submission tests cover
concurrent idempotency, deadline crossings, atomic outbox insertion, and recovery
from MinIO/PostgreSQL partial failures:

```sh
mvn clean verify -Pintegration-tests
```

Surefire runs `*Test`; the integration profile enables Failsafe for `*IT`.
Integration tests use random ports, generated RSA keys and passwords, and do not use `.env`.
Worker Docker tests require the [language toolchain images](services/judge-worker/README.md#sandbox)
to be prepared first; they exercise all ten accepted languages.

## Local development

```sh
cp .env.example .env
```

Fill in the PostgreSQL, Redis, MinIO root, and separate S3 application credentials in `.env`. Generate independent passwords with
`openssl rand -hex 24`; choose a MinIO username of at least three characters.
The example deliberately contains no usable passwords. `.env` is ignored by Git.
Use shell-compatible assignments and quote values containing shell metacharacters.

Before running the API, configure either [local authentication and signing keys](docs/authentication.md)
or [Portal SSO](docs/portal-sso.md). Local mode requires an RSA key pair; Portal mode
requires a registered OIDC client and secret. Bootstrap applies only to local mode.

Run these commands from the repository root:

```sh
docker compose --env-file .env -f infrastructure/docker/compose.yml up -d --wait
docker compose --env-file .env -f infrastructure/docker/compose.yml --profile storage-init run --rm --build minio-init
docker compose --env-file .env -f infrastructure/docker/compose.yml ps
docker compose --env-file .env -f infrastructure/docker/compose.yml logs -f
```

Services bind only to localhost: PostgreSQL `5432`, Redis `6379`, MinIO S3 API
`9000`, and MinIO console `http://localhost:9001`. Ports are configurable in `.env`.
If the PostgreSQL port or database name changes, update `DB_URL` to match. Redis
requires the configured password and persists data with append-only logging.
All three services have health checks and persistent named volumes. PostgreSQL
initialization credentials apply when its volume is first created; editing `.env`
does not rotate credentials in an existing database.

MinIO's [latest community release](https://github.com/minio/minio/releases/tag/RELEASE.2025-10-15T17-29-55Z)
instructs container users to build from source. Compose builds the pinned release
using `infrastructure/docker/minio/Dockerfile`; the first startup needs Internet
access and takes longer while Go dependencies compile. Docker supplies the Go
toolchain, so no local Go installation is required. Subsequent starts reuse the
local image. The runtime runs as a non-root user.

The storage-init profile builds the pinned MinIO client from source, creates a
private bucket, and provisions a dedicated application user with access limited to
that bucket's submission objects. It can be rerun with the same configuration.
See [Object storage](docs/storage.md) for all S3 variables, upload restrictions,
stream ownership, initialization behavior, and production provisioning.

Compose loads `.env` through the explicit flag. Spring Boot does not read `.env`
automatically, so export it before running the API in the same terminal:

```sh
set -a
. ./.env
set +a
# If using the local key files created by docs/authentication.md:
export JWT_PRIVATE_KEY_BASE64="$(openssl base64 -A -in .local/secrets/jwt-private.der)"
export JWT_PUBLIC_KEY_BASE64="$(openssl base64 -A -in .local/secrets/jwt-public.der)"
mvn -pl services/judge-api -am install -DskipTests
mvn -pl services/judge-api spring-boot:run
```

Alternatively, after `mvn clean verify` and exporting the environment:

```sh
java -jar services/judge-api/target/judge-api-0.1.0-SNAPSHOT.jar
```

The API uses PostgreSQL at startup. Flyway upgrades the original V1 baseline with
the V2 domain schema, V3 grading-history constraints, V4 authentication invariants,
V5 token-version invalidation, V6 assignment metadata invariants, V7 test-case publication rules,
V8 submission acceptance/reconciliation, V9 evaluation completion, V10 outbox delivery,
and V11 passwordless Portal identities. Hibernate uses
`ddl-auto=validate` and never creates or modifies tables. Future schemas must use
new Flyway migrations. JDBC timestamps
use UTC, and Open Session in View is disabled. Dependency health includes PostgreSQL,
Redis and MinIO. Readiness requires PostgreSQL and MinIO; Redis outages leave the
durable acceptance path available. Storage configuration is validated at startup,
and authenticated storage connectivity is checked by Actuator and on operations.

See [Database schema and grading history](docs/database-schema.md) for the entity
model, sealing workflow, constraints, indexes, and database test coverage. Each
submission references an immutable, sealed assignment revision; later assignment
or test-case changes require a new revision.

```sh
curl -fsS http://localhost:8080/api/v1/health
curl -fsS http://localhost:8080/actuator/health
curl -fsS http://localhost:8080/actuator/health/readiness
```

`/api/v1/health` reports process availability with a JSON `status` of `UP`; it does
not claim dependency availability. Actuator checks PostgreSQL, Redis and MinIO without
exposing details; readiness requires PostgreSQL/MinIO and liveness remains
available at `/actuator/health/liveness`. OpenAPI is at `/v3/api-docs` and Swagger
UI at `/swagger-ui/index.html`. Set `OPENAPI_ENABLED=false` to disable documentation.

In local mode, health/documentation GET routes and registration/login POST routes are public.
Other API routes require a signed bearer JWT. Register at `/api/auth/register`,
log in at `/api/auth/login`, and retrieve the authenticated profile at `/api/users/me`.
Roles are checked against current enabled local accounts. The API is stateless,
with no generated default user, form login, HTTP Basic, or session cookies.

Portal mode replaces credential endpoints with `/oauth2/authorization/portal`.
It supports protected browser sessions with CSRF and Portal access tokens addressed
to Judge for backend requests. Worker authentication remains separate in both modes.

See [Authentication and authorization](docs/authentication.md) for request examples,
password rules, RSA key configuration, authorization boundaries, error responses,
and the optional idempotent administrator bootstrap. Disable bootstrap and remove
its credential variables after initial provisioning.

Administrators can list, search, and filter users at `/api/admin/users`, retrieve
individual users, change STUDENT/TEACHER roles, and enable or disable accounts.
See [Administrator user management](docs/user-administration.md) for pagination,
sorting, request bodies, concurrency safeguards, and token revocation. Tokens
issued before Stage 4 require a fresh login; re-enabling an account does not
reactivate tokens issued before it was disabled.

Teachers can create and manage their assignments at `/api/assignments`;
administrators can manage all assignments. Students see published assignments
only. See [Assignment management](docs/assignments.md) for routes, payloads,
pagination, explicit revision creation, publication, and historical retention.
Publishing now requires at least one valid test, positive points summing exactly to
maximumScore, and seals the current grading revision. See [Test-case management](docs/test-cases.md)
for the five routes, hidden-data protection, and automatic draft revisions.
V7 returns Stage 5 publications without valid sealed grading to DRAFT for review;
it preserves all existing grading history.

Students can now submit multipart source files to published assignments using an
Idempotency-Key. Acceptance requires a completed MinIO upload and a committed
submission/outbox transaction. See [Student submissions](docs/submissions.md) for
language/extension rules, the post-upload deadline cutoff, scoped queries, retries,
and the enabled-by-default cleanup scheduler.

Stop services while retaining data:

```sh
docker compose --env-file .env -f infrastructure/docker/compose.yml down
```

To intentionally delete **all local service data**, use the same command with `-v`.

## Deferred functionality

`judge-worker` provides Redis consumption, Docker sandbox execution and grading for Java, Python,
C, C++, JavaScript, TypeScript, Kotlin, Go, Rust and C#. See its [README](services/judge-worker/README.md)
for image preparation, supported versions and single-file restrictions. Enable the
[internal worker transport](docs/worker-transport.md) with a separate service token to connect grading
to durable API results. Local end-to-end tests cover this path; production deployment remains separate.

Stage 9 adds [evaluation results, history, and leaderboards](docs/evaluation-results.md),
plus an internal completion service restricted to the authenticated worker.
Unavailable grades remain null. Private source downloads are available only through the worker transport.
Local registration, JWT login, initial administrator bootstrap, user/assignment/test-case
management, and student submissions are implemented. Additional ADMIN provisioning,
account deletion, refresh tokens, and password reset remain
deferred. SUBMISSION_CREATED events are stored transactionally in the outbox.
Redis Streams now receives identifier-only events with stable IDs and at-least-once delivery.
The authenticated worker transport connects evaluation to API/storage. No
untrusted code is executed by the API and no evaluation results are fabricated.
Basic API Docker and Kubernetes/k3s resources are included; provisioning backing services,
production ingress/TLS, backups, and actual deployment remain operator responsibilities.


## Stage 10 integration and operations

The outbox publisher retries unpublished events with PostgreSQL row locks and
bounded exponential backoff. Redis is a bounded delivery buffer; retained outbox
history enables replay after Redis loss/trimming. Publishing never grades a submission.

    mvn clean verify
    mvn clean verify -Pintegration-tests
    docker compose --env-file .env -f infrastructure/docker/compose.yml --profile api up -d --build --wait

The API image runs as UID 10001 on Java 25 and supports ARM64. Redis uses AOF and
persistent storage locally. Metrics are ADMIN-protected; liveness/readiness expose
status only. All configuration comes from environment variables; no new defaults
for secrets are introduced.

- [Architecture](docs/architecture.md) and [database design](docs/database-design.md)
- [API routes](docs/api.md) and [deployment/load tests](docs/deployment.md)
- [Security](docs/security.md), [delivery/recovery](docs/reliability.md)
- [Proposed SLOs, not an SLA](docs/slo-sla.md)
- [Future worker contract and boundary](docs/worker-integration.md)
- [Verification record and smoke measurements](docs/verification.md)

Proposed targets are 99.5% monthly availability, acceptance p95 <= 500 ms, burst
p99 <= 2 seconds, and zero acknowledged submission loss as a reliability objective.
They require measurement and operational validation; no concurrency capacity or
single-node high availability is claimed. Worker-to-API integration is verified locally with real dependencies.
