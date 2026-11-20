# judge-worker

Evaluates submissions. It takes `SUBMISSION_CREATED` events from the Redis stream, asks judge-api for the
source and the sealed tests, compiles and runs the code in a locked-down Docker container once per test,
and sends the result back to judge-api. It executes untrusted student code and therefore holds no
database or object-storage credentials.

```
Redis stream ──> WorkerLoop ──> SubmissionProcessor ──> JudgeApiClient.begin   (source + tests)
                                        │
                                        ├──> Grader ──> DockerSandboxRunner     (compile, then one run per test)
                                        │
                                        └──> JudgeApiClient.complete           (EvaluationCompletion report)
```

## Status

| Part | State |
| --- | --- |
| Grading, verdicts, scoring, report building | Implemented and unit-tested |
| Retry, duplicate handling, dead-letter decisions | Implemented and unit-tested |
| Docker sandbox | Real-container coverage in `DockerSandboxRunnerIT` and `LanguageSandboxIT` |
| Redis consumer (`RedisStreamQueue`) | Implemented; real Redis delivery and retries covered by `WorkerTransportIT` |
| HTTP transport to judge-api | Implemented and tested with real API, PostgreSQL, MinIO, Redis and Java Docker grading; [configuration](../../docs/worker-transport.md) |
| Languages | All ten formats accepted by the API have enabled evaluators; see the toolchain table below |

Enable `WORKER_TRANSPORT_ENABLED=true` on the API and configure the same random `JUDGE_WORKER_TOKEN`
on both processes before starting the consumer. User JWTs cannot authorize worker requests. Temporary
API/authentication failures leave events pending until the service recovers or configuration is repaired.

## Verdicts

The worker reports exactly the values of `org.university.judge.api.result.Verdict`.

| Situation | Verdict |
| --- | --- |
| Every test matches | `ACCEPTED` |
| Output differs (line endings and trailing whitespace are ignored) | `WRONG_ANSWER` |
| Compiler fails or exceeds its own limits; no test is run | `COMPILATION_ERROR` |
| Non-zero exit code, uncaught exception | `RUNTIME_ERROR` |
| More than 1 MiB of output (the API has no output-limit verdict) | `RUNTIME_ERROR` |
| Still running at time limit + start-up grace | `TIME_LIMIT_EXCEEDED` |
| Killed by the container memory limit, or `java.lang.OutOfMemoryError` | `MEMORY_LIMIT_EXCEEDED` |
| Language has no evaluator, or the sandbox kept failing on the last delivery | `INTERNAL_ERROR` (excluded from ranking) |

Every configured test is run even after a failure, because the API requires an outcome for each one.
The submission's verdict is the verdict of the first failing test in execution order.

## Sandbox

Each compilation and each test run is a new container started with:

`--network none`, `--memory` and `--memory-swap` (no swap), `--cpus`, `--pids-limit`, `--read-only`,
a small `noexec` tmpfs for `/tmp`, `--cap-drop ALL`, `--security-opt no-new-privileges`, a non-root user,
and the work directory mounted read-only for test runs. The worker kills the container at the deadline
and stops reading after the output limit. Test input goes in on stdin; expected output and hidden tests
never enter the container.

Run the worker **directly on a host that has Docker**, not in a container with `docker.sock` mounted:
access to the Docker daemon is equivalent to root on that host. Prepare the images first: the sandbox
has no network and uses `--pull never`:

```
docker compose -f infrastructure/docker/worker-toolchains/compose.yml pull --ignore-buildable
docker compose -f infrastructure/docker/worker-toolchains/compose.yml build
```

Known limits of this first version:

- The measured time includes container and JVM start-up, so `startup-grace` is added before a run counts
  as timed out. A run slightly over the limit can therefore still pass.
- One container per test is simple and safe but slow: expect roughly a second of overhead per test.
- Default seccomp profile only; no gVisor or user-namespace remapping.
- Compiler messages are discarded: the API result has no field for them, so students do not see why compilation failed.

## Supported languages

The API accepts exactly one UTF-8 source file and a case-sensitive `programmingLanguage` value.
All ten languages are enabled by default. Each program reads stdin and writes its answer to stdout.
Only the standard libraries/tooling in the image are available: no user dependency manifests,
network installs, npm packages, NuGet packages, Cargo crates or Go modules are fetched.

| API value | Extension | Default toolchain | Compilation / execution |
| --- | --- | --- | --- |
| JAVA | .java | Temurin JDK 21 | javac → java |
| PYTHON | .py | Python 3.13 | syntax check → python3 |
| C | .c | GCC 14, C17 | gcc → native executable |
| CPP | .cpp | GCC 14, C++20 | g++ → native executable |
| JAVASCRIPT | .js | Node.js 22 | syntax check → node; CommonJS or Node-supported ESM |
| TYPESCRIPT | .ts | TypeScript 5.8.3, Node.js 22 | tsc with Node type declarations → CommonJS on node |
| KOTLIN | .kt | Kotlin 2.1.20, JDK 21 | kotlinc → executable JAR; one main entry point |
| GO | .go | Go 1.24 | go build, CGO disabled → native executable; package main |
| RUST | .rs | Rust 1.85, edition 2021 | rustc → native executable |
| CSHARP | .cs | .NET SDK 8, C# 12 | Roslyn with .NET reference assemblies → dotnet; top-level statements or static Main |

TypeScript and Kotlin images are built from the Dockerfiles in
[`infrastructure/docker/worker-toolchains`](../../infrastructure/docker/worker-toolchains/compose.yml).
Image preparation requires Internet access; grading does not. The worker invokes Roslyn directly,
generates C# global usings equivalent to the console SDK defaults and a fixed .NET runtime configuration.
This avoids SDK first-run/NuGet migrations and package restore inside the sandbox. Source is never
interpolated into shell commands.

Java/Kotlin heaps, Node's old-space heap and the .NET GC heap use the configured memory limit;
their containers get an additional 160 MB for runtime overhead. Other languages use the container
limit directly. Compilation uses the separate compiler time/memory limits for every language.

Set `WORKER_LANGUAGES` to a comma-separated subset when only some toolchains are installed.
Coordinate that subset with API acceptance; a disabled language still produces `INTERNAL_ERROR`.
The API itself does not compile or run uploads. It serves private source/sealed tests and accepts
validated results through the separately authenticated worker transport.

### Java submissions

A submission is one `.java` file. The file is stored under the name of its public top-level type (or the
uploaded name, or `Main.java`), compiled with `javac -d classes`, and started with the class that declares
`main`, including its package. The heap gets the whole memory limit (`-Xmx`), the container 160 MB more
for the JVM itself.

## Delivery guarantees

- A record is acknowledged only after judge-api has stored the result (or reports the submission as
  already completed).
- Every try uses a new attempt ID. judge-api admits one attempt at a time, rejects results from a
  replaced attempt and never reopens a completed submission, so duplicate or replayed events cannot
  produce two results.
- A record whose worker died stays in the consumer group's pending list. After `reclaim-idle` another
  worker claims it. Retries are therefore spaced by `reclaim-idle`.
- A grading failure on or after `max-deliveries` produces `INTERNAL_ERROR` so the student sees a final
  state. Temporary API failures and busy leases stay pending beyond this threshold. Invalid events
  and permanently rejected reports go to `judge:submissions:dead:v1` with a `deadLetterReason`.
- A rejected stale result is dropped without acknowledging the replacement's message. Once the API
  confirms a terminal submission, a replay can safely be acknowledged.
- The consumer group is created from the start of the stream. Events already trimmed from Redis need
  the outbox replay in [docs/reliability.md](../../docs/reliability.md).
- Several workers can share the group; each takes only as many records as it has free slots.

## Configuration

Redis uses the same `REDIS_*` variables as judge-api.

| Variable | Default | Meaning |
| --- | --- | --- |
| `JUDGE_WORKER_TOKEN` | none, required | Evaluator credential for the worker transport |
| `JUDGE_API_BASE_URL` | `http://localhost:8080` | Internal address of judge-api |
| `WORKER_LANGUAGES` | All ten API languages | Comma-separated languages to evaluate |
| `WORKER_CONCURRENCY` | `2` | Evaluations in parallel; keep at or below the CPU count |
| `WORKER_TIME_LIMIT` | `5s` | Per-test time limit |
| `WORKER_MEMORY_MB` | `256` | Per-test memory limit |
| `WORKER_COMPILE_TIME_LIMIT` / `WORKER_COMPILE_MEMORY_MB` | `30s` / `512` | Compiler limits |
| `WORKER_SANDBOX_STARTUP_GRACE` | `2s` | Allowance for container start-up |
| `WORKER_MAX_DELIVERIES` | `5` | Delivery threshold for finalizing repeated grading failures |
| `WORKER_RECLAIM_IDLE` | `5m` | Idle time after which a pending record is retried |
| `WORKER_GROUP` / `WORKER_CONSUMER_NAME` | `judge-workers` / host name | Consumer group and unique consumer name |
| `WORKER_JAVA_IMAGE` | `eclipse-temurin:21-jdk` | Image used to compile and run Java |
| `WORKER_PYTHON_IMAGE` | `python:3.13-slim` | Python syntax check and runtime |
| `WORKER_GCC_IMAGE` | `gcc:14` | C/C++ compiler and runtime |
| `WORKER_NODE_IMAGE` | `node:22-bookworm-slim` | JavaScript runtime |
| `WORKER_TYPESCRIPT_IMAGE` | `judge-typescript:5.8.3` | Prebuilt TypeScript compiler and Node runtime |
| `WORKER_KOTLIN_IMAGE` | `judge-kotlin:2.1.20` | Prebuilt Kotlin compiler and JVM |
| `WORKER_GO_IMAGE` | `golang:1.24-bookworm` | Go compiler and runtime |
| `WORKER_RUST_IMAGE` | `rust:1.85-bookworm` | Rust compiler and runtime |
| `WORKER_CSHARP_IMAGE` | `mcr.microsoft.com/dotnet/sdk:8.0` | C# compiler and .NET runtime |

Time and memory limits are worker-wide because assignments carry no limits yet. API `WORKER_ATTEMPT_LEASE`
and worker `WORKER_RECLAIM_IDLE` are renewed during grading (defaults: 4m and 5m).
They must tolerate normal network latency and pauses; total grading may exceed both.
Completed execution times use Docker container timestamps, excluding CLI cleanup.
Use the [dedicated Linux worker deployment](../../infrastructure/systemd/README.md)
for a hard aggregate workspace quota; per-job byte/file monitoring alone can overshoot.
All settings are in `src/main/resources/application.yml`.

## Build, test, run

From the repository root, with JDK 25 and Maven:

```
mvn -pl services/judge-worker -am verify                        # unit tests, no Docker or Redis
mvn -pl services/judge-worker -am verify -Pintegration-tests    # all ten languages and hostile Java programs
```

```
export REDIS_PASSWORD=... JUDGE_WORKER_TOKEN=... JUDGE_API_BASE_URL=http://localhost:8080
java -jar services/judge-worker/target/judge-worker-0.1.0-SNAPSHOT-exec.jar
```

`LanguageSandboxIT` requires all images and fails if one is missing. It checks stdin, independent test
runs, scores, wrong answers, compilation errors, runtime errors and timeouts for each language.
It also checks heap exhaustion for JavaScript, TypeScript, Kotlin and C#; the original Java sandbox
suite checks Java memory exhaustion and the existing isolation controls.
These worker-local tests exercise real Docker grading. The API module's `WorkerTransportIT` additionally
checks Redis/HTTP end-to-end processing and authorization with real dependencies. Neither is a full
sandbox security audit.
