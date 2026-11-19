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
| Docker sandbox | Implemented. Its integration test (`DockerSandboxRunnerIT`) has **not** been run against a real Docker daemon yet |
| Redis consumer (`RedisStreamQueue`) | Implemented, **not yet run against Redis** |
| HTTP transport to judge-api | Client implemented and tested against a local stand-in. **judge-api does not expose these routes yet** — see [docs/worker-transport.md](../../docs/worker-transport.md) |
| Languages | `JAVA` enabled. `PYTHON`, `C`, `CPP` are written but disabled and untested in a real sandbox |

Until judge-api implements the transport the worker starts, reads events, fails every `begin` call and
leaves the records pending in Redis. Nothing is lost, and nothing is graded.

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
access to the Docker daemon is equivalent to root on that host. Pull the image first, the sandbox has
no network:

```
docker pull eclipse-temurin:21-jdk
```

Known limits of this first version:

- The measured time includes container and JVM start-up, so `startup-grace` is added before a run counts
  as timed out. A run slightly over the limit can therefore still pass.
- One container per test is simple and safe but slow: expect roughly a second of overhead per test.
- Default seccomp profile only; no gVisor or user-namespace remapping.
- Compiler messages are discarded: the API result has no field for them, so students do not see why compilation failed.

## Java submissions

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
- After `max-deliveries` the record goes to the dead-letter stream `judge:submissions:dead:v1` with a
  `deadLetterReason`. If the evaluation itself kept failing, the submission is first completed as
  `INTERNAL_ERROR` so the student sees a final state.
- The consumer group is created from the start of the stream. Events already trimmed from Redis need
  the outbox replay in [docs/reliability.md](../../docs/reliability.md).
- Several workers can share the group; each takes only as many records as it has free slots.

## Configuration

Redis uses the same `REDIS_*` variables as judge-api.

| Variable | Default | Meaning |
| --- | --- | --- |
| `JUDGE_WORKER_TOKEN` | none, required | Evaluator credential for the worker transport |
| `JUDGE_API_BASE_URL` | `http://localhost:8080` | Internal address of judge-api |
| `WORKER_LANGUAGES` | `JAVA` | Comma-separated languages to evaluate |
| `WORKER_CONCURRENCY` | `2` | Evaluations in parallel; keep at or below the CPU count |
| `WORKER_TIME_LIMIT` | `5s` | Per-test time limit |
| `WORKER_MEMORY_MB` | `256` | Per-test memory limit |
| `WORKER_COMPILE_TIME_LIMIT` / `WORKER_COMPILE_MEMORY_MB` | `30s` / `512` | Compiler limits |
| `WORKER_SANDBOX_STARTUP_GRACE` | `2s` | Allowance for container start-up |
| `WORKER_MAX_DELIVERIES` | `5` | Deliveries before dead-lettering |
| `WORKER_RECLAIM_IDLE` | `5m` | Idle time after which a pending record is retried |
| `WORKER_GROUP` / `WORKER_CONSUMER_NAME` | `judge-workers` / host name | Consumer group and unique consumer name |
| `WORKER_JAVA_IMAGE` | `eclipse-temurin:21-jdk` | Image used to compile and run Java |

Time and memory limits are worker-wide because assignments carry no limits yet. `WORKER_RECLAIM_IDLE`
must exceed the longest evaluation: compile limit + tests x (time limit + grace).
All settings are in `src/main/resources/application.yml`.

## Build, test, run

From the repository root, with JDK 25 and Maven:

```
mvn -pl services/judge-worker -am verify                        # unit tests, no Docker or Redis
mvn -pl services/judge-worker -am verify -Pintegration-tests    # plus hostile Java programs in real Docker
```

```
export REDIS_PASSWORD=... JUDGE_WORKER_TOKEN=... JUDGE_API_BASE_URL=http://localhost:8080
java -jar services/judge-worker/target/judge-worker-0.1.0-SNAPSHOT.jar
```
