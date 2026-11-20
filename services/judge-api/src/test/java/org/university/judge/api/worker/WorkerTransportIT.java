package org.university.judge.api.worker;

import static org.assertj.core.api.Assertions.*;

import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.university.judge.api.JudgeApiApplication;
import org.university.judge.api.outbox.OutboxPublisher;
import org.university.judge.api.support.TestJwtKeys;
import org.university.judge.api.user.*;
import org.university.judge.storage.ObjectStorageService;
import org.university.judge.worker.client.HttpJudgeApiClient;
import org.university.judge.worker.config.WorkerProperties;
import org.university.judge.worker.grading.Grader;
import org.university.judge.worker.grading.LanguageProfiles;
import org.university.judge.worker.processing.SubmissionProcessor;
import org.university.judge.worker.queue.RedisStreamQueue;
import org.university.judge.worker.queue.WorkerLoop;
import org.university.judge.worker.sandbox.DockerSandboxRunner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class WorkerTransportIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withPassword(UUID.randomUUID().toString());
    private static final String S3_USER = "test-" + UUID.randomUUID();
    private static final String S3_PASSWORD = UUID.randomUUID().toString();
    private static final String REDIS_PASSWORD = UUID.randomUUID().toString();
    private static final String WORKER_TOKEN = UUID.randomUUID().toString() + UUID.randomUUID();
    private static final String PASSWORD = "Worker9!" + UUID.randomUUID();
    private static final String STREAM = "judge:worker-transport:test";
    private static final Path ROOT = Path.of(System.getProperty("basedir", ".")).resolve("../..").normalize().toAbsolutePath();
    @Container static final GenericContainer<?> MINIO = new GenericContainer<>(
            new ImageFromDockerfile("online-judge-minio:RELEASE.2025-10-15T17-29-55Z", false)
                    .withDockerfile(ROOT.resolve("infrastructure/docker/minio/Dockerfile")))
            .withEnv("MINIO_ROOT_USER", S3_USER).withEnv("MINIO_ROOT_PASSWORD", S3_PASSWORD)
            .withCommand("server", "/data").withExposedPorts(9000).waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8-alpine")
            .withCommand("redis-server", "--requirepass", REDIS_PASSWORD).withExposedPorts(6379);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String SOURCE = """
            public class Main { public static void main(String[] args) {
                var s = new java.util.Scanner(System.in); System.out.println(s.nextInt() + s.nextInt());
            } }
            """;
    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static StringRedisTemplate redis;
    private static String base, hash;
    private String studentToken, teacherToken, adminToken;
    private UUID assignment, submission, event;

    @BeforeAll static void start() throws Exception {
        String storageUrl = "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
        MinioClient.builder().endpoint(storageUrl).credentials(S3_USER, S3_PASSWORD).build()
                .makeBucket(MakeBucketArgs.builder().bucket("worker-test").build());
        context = new SpringApplicationBuilder(JudgeApiApplication.class).run(TestJwtKeys.arguments(
                "--server.port=0", "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(), "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.data.redis.host=" + REDIS.getHost(), "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                "--spring.data.redis.password=" + REDIS_PASSWORD, "--storage.s3.endpoint=" + storageUrl,
                "--storage.s3.access-key=" + S3_USER, "--storage.s3.secret-key=" + S3_PASSWORD, "--storage.s3.bucket=worker-test",
                "--worker.transport.enabled=true", "--worker.transport.token=" + WORKER_TOKEN,
                "--worker.recovery.minimum-age=0ms", "--worker.recovery.interval=PT24H",
                "--worker.transport.attempt-lease=2s", "--submission.cleanup.enabled=false", "--outbox.stream=" + STREAM));
        jdbc = context.getBean(JdbcTemplate.class);
        redis = context.getBean(StringRedisTemplate.class);
        hash = context.getBean(PasswordEncoder.class).encode(PASSWORD);
        base = "http://localhost:" + context.getEnvironment().getRequiredProperty("local.server.port");
    }

    @BeforeEach void setup() throws Exception {
        // Each case has a private fixture; only the current case is published by the manual publisher.
        jdbc.update("UPDATE outbox_events SET published_at = clock_timestamp() WHERE published_at IS NULL");
        redis.delete(List.of(STREAM, STREAM + ":dead"));
        studentToken = account(Role.STUDENT); teacherToken = account(Role.TEACHER); adminToken = account(Role.ADMIN);
        assignment = UUID.fromString(body(send("POST", "/api/assignments", teacherToken,
                Map.of("title", "Transport test", "description", "Add two integers", "maximumScore", 100,
                        "deadline", Instant.now().plusSeconds(3600).toString())), 201).get("id").asText());
        addTest("2 3", "5", 40, false, 0);
        addTest("9 8", "17", 60, true, 1);
        body(send("POST", "/api/assignments/" + assignment + "/publish", teacherToken, null), 200);
        submission = upload();
        event = jdbc.queryForObject("SELECT id FROM outbox_events WHERE aggregate_id = ?", UUID.class, submission);
    }

    @AfterAll static void stop() { if (context != null) context.close(); }

    @Test void workerAndUserCredentialsCannotCrossSecurityBoundaries() throws Exception {
        UUID attempt = UUID.randomUUID();
        for (String token : List.of("", "invalid", studentToken, teacherToken, adminToken)) {
            assertThat(begin(attempt, token).statusCode()).isEqualTo(401);
            assertThat(send("POST", resultPath(attempt), token, Map.of()).statusCode()).isEqualTo(401);
        }
        assertThat(send("GET", "/api/users/me", WORKER_TOKEN, null).statusCode()).isEqualTo(401);
        assertThat(send("GET", "/api/admin/users", WORKER_TOKEN, null).statusCode()).isEqualTo(401);
        assertThat(send("GET", beginPath(), WORKER_TOKEN, null).statusCode()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT status FROM submissions WHERE id = ?", String.class, submission)).isEqualTo("PENDING");
        assertThat(send("GET", "/v3/api-docs", null, null).body()).doesNotContain("/internal/worker");
    }

    @Test void beginReturnsOriginalSealedTestsAndReplaysWithoutIncreasingAttemptCount() throws Exception {
        var revision = jdbc.queryForObject("SELECT assignment_revision_id FROM submissions WHERE id = ?", UUID.class, submission);
        body(send("PUT", "/api/assignments/" + assignment + "?createNewRevision=true", teacherToken,
                Map.of("title", "New revision", "description", "Different instructions", "maximumScore", 100,
                        "deadline", Instant.now().plusSeconds(3600).toString())), 200);
        UUID attempt = UUID.randomUUID();
        var response = begin(attempt, WORKER_TOKEN);
        var job = body(response, 200);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(job.get("assignmentRevisionId").asText()).isEqualTo(revision.toString());
        assertThat(job.get("source").asText()).isEqualTo(SOURCE);
        assertThat(job.get("tests").get(1).get("expectedOutput").asText()).isEqualTo("17");
        assertThat(job.toString()).doesNotContain("sourceFileKey", S3_PASSWORD, WORKER_TOKEN);
        assertThat(body(begin(attempt, WORKER_TOKEN), 200)).isEqualTo(job);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM submissions WHERE id = ?", Integer.class, submission)).isEqualTo(1);
    }

    @Test void mismatchedEventsAndMalformedRequestsCannotClaimWork() throws Exception {
        assertThat(send("POST", beginPath(), WORKER_TOKEN,
                Map.of("attemptId", UUID.randomUUID(), "eventId", UUID.randomUUID())).statusCode()).isEqualTo(422);
        assertThat(send("POST", beginPath(), WORKER_TOKEN, Map.of("eventId", event)).statusCode()).isEqualTo(400);
        assertThat(send("POST", "/internal/worker/v1/submissions/" + UUID.randomUUID() + "/attempts", WORKER_TOKEN,
                Map.of("attemptId", UUID.randomUUID(), "eventId", event)).statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT status FROM submissions WHERE id = ?", String.class, submission)).isEqualTo("PENDING");
    }

    @Test void concurrentClaimsAdmitOnlyOneLiveAttempt() throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Integer> claim = () -> { barrier.await(); return begin(UUID.randomUUID(), WORKER_TOKEN).statusCode(); };
            var first = pool.submit(claim); var second = pool.submit(claim);
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM submissions WHERE id = ?", Integer.class, submission)).isEqualTo(1);
    }

    @Test void expiredLeaseAllowsRecoveryAndRejectsTheOldResult() throws Exception {
        UUID old = UUID.randomUUID();
        var oldJob = body(begin(old, WORKER_TOKEN), 200);
        UUID replacement = UUID.randomUUID();
        assertThat(begin(replacement, WORKER_TOKEN).statusCode()).isEqualTo(409);
        Thread.sleep(2100);
        var job = body(begin(replacement, WORKER_TOKEN), 200);
        assertThat(send("POST", beginPath() + "/" + old + "/heartbeat", WORKER_TOKEN, Map.of()).statusCode()).isEqualTo(409);
        assertThat(send("POST", resultPath(old), WORKER_TOKEN, report(oldJob)).statusCode()).isEqualTo(409);
        assertThat(send("POST", resultPath(replacement), WORKER_TOKEN, report(job)).statusCode()).isEqualTo(204);
    }

    @Test void heartbeatKeepsAnAttemptAliveBeyondItsOriginalLease() throws Exception {
        UUID attempt = UUID.randomUUID();
        var job = body(begin(attempt, WORKER_TOKEN), 200);
        var client = new HttpJudgeApiClient(workerProperties(Duration.ofMinutes(5), 5));
        for (int i = 0; i < 4; i++) {
            Thread.sleep(700);
            assertThat(client.heartbeat(submission, attempt)).isEqualTo(2000);
            assertThat(begin(UUID.randomUUID(), WORKER_TOKEN).statusCode()).isEqualTo(409);
        }
        assertThat(send("POST", resultPath(attempt), WORKER_TOKEN, report(job)).statusCode()).isEqualTo(204);
        assertThat(send("POST", beginPath() + "/" + attempt + "/heartbeat", WORKER_TOKEN, Map.of()).statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM submissions WHERE id = ?", Integer.class, submission)).isEqualTo(1);
    }

    @Test void lostRedisGroupIsRecreatedWithoutRestartingTheConsumer() {
        var properties = workerProperties(Duration.ofMinutes(5), 5);
        var queue = new RedisStreamQueue(redis, properties);
        context.getBean(OutboxPublisher.class).publishBatch();
        assertThat(queue.poll(1, Duration.ofMillis(10))).hasSize(1);
        redis.opsForStream().destroyGroup(STREAM, properties.group());
        assertThat(queue.poll(1, Duration.ofMillis(10))).hasSize(1);
        redis.delete(STREAM);
        assertThat(queue.poll(1, Duration.ofMillis(10))).isEmpty();
    }

    @Test void expiredRunningDeliveryIsRepublishedButLiveAndCompletedWorkIsNot() throws Exception {
        UUID attempt = UUID.randomUUID();
        var job = body(begin(attempt, WORKER_TOKEN), 200);
        context.getBean(OutboxPublisher.class).publishBatch();
        redis.delete(STREAM);
        var recovery = context.getBean(EvaluationRecovery.class);
        recovery.recover();
        assertThat(jdbc.queryForObject("SELECT published_at IS NOT NULL FROM outbox_events WHERE id = ?", Boolean.class, event)).isTrue();
        jdbc.update("UPDATE submissions SET evaluation_lease_until = clock_timestamp() - interval '1 second' WHERE id = ?", submission);
        recovery.recover();
        assertThat(jdbc.queryForObject("SELECT published_at IS NULL FROM outbox_events WHERE id = ?", Boolean.class, event)).isTrue();
        context.getBean(OutboxPublisher.class).publishBatch();
        var queue = new RedisStreamQueue(redis, workerProperties(Duration.ofMinutes(5), 5));
        assertThat(queue.poll(1, Duration.ofMillis(10))).hasSize(1);
        assertThat(send("POST", resultPath(attempt), WORKER_TOKEN, report(job)).statusCode()).isEqualTo(204);
        recovery.recover();
        assertThat(jdbc.queryForObject("SELECT published_at IS NOT NULL FROM outbox_events WHERE id = ?", Boolean.class, event)).isTrue();
    }

    @Test void pendingScanAdvancesPastTheFirstHundredInFlightRecords() {
        var properties = workerProperties(Duration.ZERO, 5);
        var queue = new RedisStreamQueue(redis, properties);
        for (int i = 0; i < 105; i++) redis.opsForStream().add(STREAM, Map.of("index", Integer.toString(i)));
        var delivered = queue.poll(105, Duration.ofMillis(10));
        var inFlight = new HashSet<String>();
        delivered.stream().limit(100).forEach(e -> inFlight.add(e.recordId()));
        assertThat(queue.reclaimStale(5, inFlight)).isEmpty();
        assertThat(queue.reclaimStale(5, inFlight)).hasSize(5);
    }

    @Test void completionValidatesScoresIsIdempotentAndKeepsHiddenResultsPrivate() throws Exception {
        UUID attempt = UUID.randomUUID();
        var job = body(begin(attempt, WORKER_TOKEN), 200);
        var report = report(job);
        var invalid = new HashMap<>(report); invalid.put("totalScore", "99");
        assertThat(send("POST", resultPath(attempt), WORKER_TOKEN, invalid).statusCode()).isEqualTo(422);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM submission_results WHERE submission_id = ?", Integer.class, submission)).isZero();
        assertThat(send("POST", resultPath(attempt), WORKER_TOKEN, report).statusCode()).isEqualTo(204);
        assertThat(send("POST", resultPath(attempt), WORKER_TOKEN, report).statusCode()).isEqualTo(204);
        var conflicting = new HashMap<>(report); conflicting.put("executionTimeMs", 2);
        assertThat(send("POST", resultPath(attempt), WORKER_TOKEN, conflicting).statusCode()).isEqualTo(409);
        assertThat(begin(UUID.randomUUID(), WORKER_TOKEN).statusCode()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM submission_results WHERE submission_id = ?", Integer.class, submission)).isEqualTo(1);
        var result = body(send("GET", "/api/submissions/" + submission + "/result", studentToken, null), 200);
        assertThat(result.get("verdict").asText()).isEqualTo("ACCEPTED");
        var hidden = result.get("testResults").get(1);
        for (String field : List.of("inputData", "expectedOutput", "actualOutput")) {
            assertThat(hidden.path(field).isMissingNode() || hidden.path(field).isNull()).as(field).isTrue();
        }
    }

    @Test void unavailableSourceDoesNotStrandAnAttemptOrMasqueradeAsUnknownSubmission() throws Exception {
        String key = jdbc.queryForObject("SELECT source_file_key FROM submissions WHERE id = ?", String.class, submission);
        context.getBean(ObjectStorageService.class).delete(key);
        assertThat(begin(UUID.randomUUID(), WORKER_TOKEN).statusCode()).isEqualTo(503);
        assertThat(jdbc.queryForObject("SELECT status FROM submissions WHERE id = ?", String.class, submission)).isEqualTo("PENDING");
    }

    @Test void realWorkerConsumesRedisGradesInDockerAndStoresAnObservableResult() throws Exception {
        var properties = workerProperties(Duration.ofMinutes(5), 5);
        var languages = new LanguageProfiles(properties);
        var queue = new RedisStreamQueue(redis, properties);
        var processor = new SubmissionProcessor(new HttpJudgeApiClient(properties),
                new Grader(new DockerSandboxRunner(properties), languages, properties), properties);
        var loop = new WorkerLoop(queue, processor, properties, languages);
        context.getBean(OutboxPublisher.class).publishBatch();
        loop.start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
            JsonNode result;
            do {
                result = body(send("GET", "/api/submissions/" + submission + "/result", studentToken, null), 200);
                if (result.get("status").asText().equals("COMPLETED")) break;
                Thread.sleep(100);
            } while (System.nanoTime() < deadline);
            assertThat(result.get("status").asText()).isEqualTo("COMPLETED");
            assertThat(result.get("verdict").asText()).isEqualTo("ACCEPTED");
            assertThat(result.get("totalScore").decimalValue()).isEqualByComparingTo("100");
        } finally { loop.stop(); }
        assertThat(redis.opsForStream().pending(STREAM, properties.group()).getTotalPendingMessages()).isZero();
        assertThat(jdbc.queryForObject("SELECT published_at IS NOT NULL FROM outbox_events WHERE id = ?", Boolean.class, event)).isTrue();
    }

    @Test void repeatedTransientDeliveriesStayPendingUntilTheApiCanAcceptThem() throws Exception {
        var properties = workerProperties(Duration.ZERO, 2);
        var queue = new RedisStreamQueue(redis, properties);
        context.getBean(OutboxPublisher.class).publishBatch();
        var first = queue.poll(1, Duration.ofMillis(10)).getFirst();
        for (int i = 0; i < 5; i++) {
            var retry = queue.reclaimStale(1, Set.of());
            assertThat(retry).hasSize(1);
            assertThat(retry.getFirst().recordId()).isEqualTo(first.recordId());
        }
        assertThat(redis.opsForStream().pending(STREAM, properties.group()).getTotalPendingMessages()).isEqualTo(1);
        assertThat(redis.opsForStream().size(properties.deadLetterStream())).isZero();
    }

    private static WorkerProperties workerProperties(Duration reclaimIdle, int deliveries) {
        return new WorkerProperties(STREAM, STREAM + ":dead", "test-workers", "test-worker", 1, deliveries,
                Duration.ofMillis(50), reclaimIdle, Duration.ofSeconds(30),
                new WorkerProperties.Api(base, WORKER_TOKEN, Duration.ofSeconds(2), Duration.ofSeconds(15)),
                new WorkerProperties.Limits(Duration.ofSeconds(2), 256, Duration.ofSeconds(30), 512, 1048576, 16384),
                new WorkerProperties.Sandbox("docker", 1, 128, 64, Duration.ofSeconds(3)),
                new WorkerProperties.Languages(List.of("JAVA"), "eclipse-temurin:21-jdk", null, null,
                        null, null, null, null, null, null));
    }

    @Test void aSecondWorkerCannotStealALongEvaluation() throws Exception {
        var first = workerProperties(Duration.ofMillis(600), 5);
        var second = new WorkerProperties(first.stream(), first.deadLetterStream(), first.group(), "second-worker", 1,
                first.maxDeliveries(), first.blockTimeout(), first.reclaimIdle(), first.shutdownGrace(), first.api(),
                first.limits(), first.sandbox(), first.languages());
        var compiling = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var languages = new LanguageProfiles(first);
        var grader = new Grader(request -> {
            if (request.workDirWritable()) {
                compiling.countDown();
                try { if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Test timed out"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }
            return new org.university.judge.worker.sandbox.RunResult(
                    org.university.judge.worker.sandbox.RunResult.Outcome.COMPLETED, 0,
                    request.stdin().contains("9") ? "17" : "5", "", 1);
        }, languages, first);
        var one = new WorkerLoop(new RedisStreamQueue(redis, first),
                new SubmissionProcessor(new HttpJudgeApiClient(first), grader, first), first, languages);
        var two = new WorkerLoop(new RedisStreamQueue(redis, second),
                new SubmissionProcessor(new HttpJudgeApiClient(second), grader, second), second, languages);
        context.getBean(OutboxPublisher.class).publishBatch();
        one.start();
        try {
            assertThat(compiling.await(10, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(2600); // Past both the original API lease and Redis idle threshold.
            two.start();
            Thread.sleep(1000);
            assertThat(jdbc.queryForObject("SELECT attempt_count FROM submissions WHERE id = ?", Integer.class, submission)).isEqualTo(1);
            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!"COMPLETED".equals(jdbc.queryForObject("SELECT status FROM submissions WHERE id = ?", String.class, submission))
                    && System.nanoTime() < deadline) Thread.sleep(50);
            assertThat(jdbc.queryForObject("SELECT status FROM submissions WHERE id = ?", String.class, submission)).isEqualTo("COMPLETED");
            assertThat(jdbc.queryForObject("SELECT attempt_count FROM submissions WHERE id = ?", Integer.class, submission)).isEqualTo(1);
        } finally { release.countDown(); one.stop(); two.stop(); }
    }

    @Test void oldQueueOwnerCannotRefreshOrDeadLetterAClaimedRecord() {
        var properties = workerProperties(Duration.ZERO, 5);
        var queue = new RedisStreamQueue(redis, properties);
        context.getBean(OutboxPublisher.class).publishBatch();
        var record = queue.poll(1, Duration.ofMillis(10)).getFirst();
        redis.opsForStream().claim(STREAM, properties.group(), "replacement", Duration.ZERO,
                org.springframework.data.redis.connection.stream.RecordId.of(record.recordId()));
        queue.touch(record);
        queue.deadLetter(record, "stale-owner");
        var pending = redis.opsForStream().pending(STREAM, properties.group(), org.springframework.data.domain.Range.unbounded(), 10);
        assertThat(pending.iterator().next().getConsumerName()).isEqualTo("replacement");
        assertThat(redis.opsForStream().size(properties.deadLetterStream())).isZero();
        redis.opsForStream().claim(STREAM, properties.group(), properties.consumerName(), Duration.ZERO,
                org.springframework.data.redis.connection.stream.RecordId.of(record.recordId()));
        queue.deadLetter(record, "current-owner");
        assertThat(redis.opsForStream().pending(STREAM, properties.group()).getTotalPendingMessages()).isZero();
        assertThat(redis.opsForStream().size(properties.deadLetterStream())).isEqualTo(1);
    }

    private void addTest(String input, String output, int points, boolean hidden, int order) throws Exception {
        body(send("POST", "/api/assignments/" + assignment + "/test-cases", teacherToken,
                Map.of("inputData", input, "expectedOutput", output, "points", points, "isHidden", hidden, "executionOrder", order)), 201);
    }
    private static String account(Role role) throws Exception {
        String name = "u" + UUID.randomUUID().toString().replace("-", "");
        context.getBean(UserRepository.class).saveAndFlush(new User(name, name + "@example.test", hash, role));
        return body(send("POST", "/api/auth/login", null, Map.of("identifier", name, "password", PASSWORD)), 200)
                .get("accessToken").asText();
    }
    private UUID upload() throws Exception {
        String boundary = "upload-" + UUID.randomUUID();
        String multipart = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"programmingLanguage\"\r\n\r\nJAVA\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"Main.java\"\r\n"
                + "Content-Type: text/plain\r\n\r\n" + SOURCE + "\r\n--" + boundary + "--\r\n";
        var request = HttpRequest.newBuilder(URI.create(base + "/api/assignments/" + assignment + "/submissions"))
                .header("Authorization", "Bearer " + studentToken).header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(multipart, StandardCharsets.UTF_8)).build();
        return UUID.fromString(body(HTTP.send(request, HttpResponse.BodyHandlers.ofString()), 201).get("submissionId").asText());
    }
    private String beginPath() { return "/internal/worker/v1/submissions/" + submission + "/attempts"; }
    private String resultPath(UUID attempt) { return beginPath() + "/" + attempt + "/result"; }
    private HttpResponse<String> begin(UUID attempt, String token) throws Exception {
        return send("POST", beginPath(), token, Map.of("attemptId", attempt, "eventId", event));
    }
    private static Map<String, Object> report(JsonNode job) {
        var tests = new ArrayList<Map<String, Object>>();
        for (var test : job.get("tests")) tests.add(Map.of("testCaseId", test.get("testCaseId").asText(), "status", "ACCEPTED",
                "awardedPoints", test.get("points").asText(), "executionTimeMs", 1, "actualOutput", test.get("expectedOutput").asText()));
        return Map.of("verdict", "ACCEPTED", "totalScore", "100", "passedTests", 2, "totalTests", 2, "executionTimeMs", 1, "tests", tests);
    }
    private static HttpResponse<String> send(String method, String path, String token, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20));
        if (token != null && !token.isEmpty()) request.header("Authorization", "Bearer " + token);
        if (body != null) request.header("Content-Type", "application/json");
        return HTTP.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
    private static JsonNode body(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return JSON.readTree(response.body());
    }
}
