package org.university.judge.api.outbox;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import io.minio.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.university.judge.api.JudgeApiApplication;
import org.university.judge.api.observability.PlatformMetrics;
import org.university.judge.api.support.TestJwtKeys;
import org.university.judge.storage.ObjectStorageService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class OutboxIntegrationIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withPassword(UUID.randomUUID().toString());
    private static final String REDIS_PASSWORD = UUID.randomUUID().toString();
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379)
            .withCommand("redis-server", "--appendonly", "yes", "--appendfsync", "always", "--requirepass", REDIS_PASSWORD);
    private static final String S3_KEY = "test-" + UUID.randomUUID(), S3_SECRET = UUID.randomUUID().toString();
    private static final Path ROOT = Path.of(System.getProperty("basedir", ".")).resolve("../..").normalize().toAbsolutePath();
    @Container static final GenericContainer<?> MINIO = new GenericContainer<>(new ImageFromDockerfile("online-judge-minio:RELEASE.2025-10-15T17-29-55Z", false)
            .withDockerfile(ROOT.resolve("infrastructure/docker/minio/Dockerfile"))).withEnv("MINIO_ROOT_USER", S3_KEY).withEnv("MINIO_ROOT_PASSWORD", S3_SECRET)
            .withCommand("server", "/data").withExposedPorts(9000).waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));
    private static final String PASSWORD = "Integration9!" + UUID.randomUUID();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final String STREAM = "judge:test:submissions";
    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static StringRedisTemplate redis;
    private static String base;
    private String adminToken, studentToken, teacherToken;
    private UUID assignment;

    @BeforeAll static void startAll() throws Exception {
        MinioClient.builder().endpoint(endpoint()).credentials(S3_KEY, S3_SECRET).build().makeBucket(MakeBucketArgs.builder().bucket("outbox-test").build());
        start(false);
    }
    private static ConfigurableApplicationContext application(boolean automatic) {
        return new SpringApplicationBuilder(JudgeApiApplication.class).run(TestJwtKeys.arguments(
            "--server.port=0", "--spring.datasource.url=" + POSTGRES.getJdbcUrl(), "--spring.datasource.username=" + POSTGRES.getUsername(), "--spring.datasource.password=" + POSTGRES.getPassword(),
            "--spring.data.redis.host=" + REDIS.getHost(), "--spring.data.redis.port=" + REDIS.getMappedPort(6379), "--spring.data.redis.password=" + REDIS_PASSWORD,
            "--spring.data.redis.timeout=500ms", "--spring.data.redis.connect-timeout=500ms", "--storage.s3.endpoint=" + endpoint(),
            "--storage.s3.access-key=" + S3_KEY, "--storage.s3.secret-key=" + S3_SECRET, "--storage.s3.bucket=outbox-test",
            "--security.bootstrap.enabled=true", "--security.bootstrap.username=initialadmin", "--security.bootstrap.email=admin@example.test", "--security.bootstrap.password=" + PASSWORD,
            "--outbox.enabled=" + automatic, "--outbox.stream=" + STREAM, "--outbox.max-length=5", "--outbox.retry-initial-delay=1s", "--outbox.retry-max-delay=2s", "--outbox.poll-interval=PT0.1S",
            "--management.health.s3.enabled=true", "--management.endpoint.health.group.readiness.include=readinessState,db,s3", "--management.endpoint.health.group.dependencies.include=db,redis,s3",
            "--submission.cleanup.enabled=false", "--observability.snapshot-interval=PT1H"));
    }
    private static void start(boolean automatic) {
        context = application(automatic); jdbc = context.getBean(JdbcTemplate.class); redis = context.getBean(StringRedisTemplate.class);
        base = "http://localhost:" + context.getEnvironment().getRequiredProperty("local.server.port");
    }
    @AfterAll static void stop() { if (context != null) context.close(); }
    @BeforeEach void setup() throws Exception {
        jdbc.execute("TRUNCATE outbox_events"); redis.delete(STREAM);
        adminToken = login("initialadmin");
        var student = register(); studentToken = login(student.get("username").asText());
        var teacher = register();
        body(send("PATCH", "/api/admin/users/" + teacher.get("id").asText() + "/role", adminToken, Map.of("role", "TEACHER")), 200);
        teacherToken = login(teacher.get("username").asText());
        var created = body(send("POST", "/api/assignments", teacherToken, Map.of("title", "Integrated assignment", "description", "Instructions", "maximumScore", 100, "deadline", Instant.now().plusSeconds(3600).toString())), 201);
        assignment = UUID.fromString(created.get("id").asText());
        body(send("POST", "/api/assignments/" + assignment + "/test-cases", teacherToken, Map.of("inputData", "secret input", "expectedOutput", "secret expected", "points", 100, "isHidden", true, "executionOrder", 0)), 201);
        body(send("POST", "/api/assignments/" + assignment + "/publish", teacherToken, null), 200);
    }

    @Test void fullUserWorkflowStoresPrivateSourceAndPublishesOnlyIdentifiers() throws Exception {
        UUID submission = submit(UUID.randomUUID().toString());
        var row = jdbc.queryForMap("SELECT * FROM submissions WHERE id = ?", submission);
        assertThat(context.getBean(ObjectStorageService.class).exists((String) row.get("source_file_key"))).isTrue();
        var event = jdbc.queryForMap("SELECT * FROM outbox_events WHERE aggregate_id = ?", submission);
        assertThat(event.get("published_at")).isNull();
        publish();
        var entries = redis.opsForStream().range(STREAM, Range.unbounded());
        assertThat(entries).hasSize(1);
        var fields = entries.getFirst().getValue();
        assertThat(fields.keySet()).containsExactlyInAnyOrder("eventId", "eventType", "eventVersion", "submissionId", "assignmentId", "assignmentRevisionId", "createdAt");
        assertThat(fields.get("eventId")).isEqualTo(event.get("id").toString());
        assertThat(fields.get("submissionId")).isEqualTo(submission.toString());
        assertThat(fields.get("eventVersion")).isEqualTo("1");
        assertThat(fields.toString()).doesNotContain("secret input", "secret expected", "class Main", PASSWORD, S3_SECRET, "sourceFileKey");
        assertThat(jdbc.queryForObject("SELECT published_at IS NOT NULL FROM outbox_events WHERE aggregate_id = ?", Boolean.class, submission)).isTrue();
        assertThat(body(send("GET", "/api/submissions/" + submission, studentToken, null), 200).get("status").asText()).isEqualTo("PENDING");
        assertThat(body(send("GET", "/api/submissions/" + submission + "/result", studentToken, null), 200).path("totalScore").isNull()).isTrue();
        assertThat(body(send("GET", "/api/users/me/submissions", studentToken, null), 200).get("totalElements").asLong()).isEqualTo(1);
        var stranger = register(); String otherToken = login(stranger.get("username").asText());
        body(send("GET", "/api/submissions/" + submission, otherToken, null), 404);
        body(send("GET", "/api/submissions/" + submission + "/result", otherToken, null), 404);
        body(send("GET", "/api/admin/users", studentToken, null), 403);
        body(send("GET", "/api/users/me", "invalid", null), 401);
    }

    @Test void redisOutageDoesNotLoseAcceptedWorkAndPublisherRestartRecovers() throws Exception {
        DockerClientFactory.instance().client().pauseContainerCmd(REDIS.getContainerId()).exec();
        UUID submission;
        try {
            submission = submit(UUID.randomUUID().toString()); publish();
            assertThat(jdbc.queryForObject("SELECT last_error_code FROM outbox_events WHERE aggregate_id = ?", String.class, submission)).isEqualTo("REDIS_UNAVAILABLE");
            assertThat(jdbc.queryForObject("SELECT published_at IS NULL FROM outbox_events WHERE aggregate_id = ?", Boolean.class, submission)).isTrue();
            body(send("GET", "/actuator/health/readiness", null, null), 200);
            body(send("GET", "/actuator/health/dependencies", null, null), 503);
        } finally { DockerClientFactory.instance().client().unpauseContainerCmd(REDIS.getContainerId()).exec(); }
        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE username = 'initialadmin'", String.class);
        context.close(); start(true);
        try {
            await(() -> Boolean.TRUE.equals(jdbc.queryForObject("SELECT published_at IS NOT NULL FROM outbox_events WHERE aggregate_id = ?", Boolean.class, submission)));
            assertThat(jdbc.queryForObject("SELECT password_hash FROM users WHERE username = 'initialadmin'", String.class)).isEqualTo(hash);
            assertThat(jdbc.queryForObject("SELECT status FROM submissions WHERE id = ?", String.class, submission)).isEqualTo("PENDING");
        } finally { context.close(); start(false); }
    }

    @Test void redisAcknowledgedButDatabaseFailedProducesDuplicateStableEventIdAfterRestart() throws Exception {
        UUID submission = submit(UUID.randomUUID().toString());
        jdbc.execute("CREATE FUNCTION reject_publish_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.published_at IS NOT NULL THEN RAISE EXCEPTION 'test commit failure' USING ERRCODE = '23514'; END IF; RETURN NEW; END; $$");
        jdbc.execute("CREATE TRIGGER reject_publish_test BEFORE UPDATE ON outbox_events FOR EACH ROW EXECUTE FUNCTION reject_publish_test()");
        try {
            publish(); assertThat(redis.opsForStream().size(STREAM)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT published_at IS NULL FROM outbox_events WHERE aggregate_id = ?", Boolean.class, submission)).isTrue();
        } finally { jdbc.execute("DROP TRIGGER reject_publish_test ON outbox_events"); jdbc.execute("DROP FUNCTION reject_publish_test()"); }
        context.close(); start(false); publish();
        var messages = redis.opsForStream().range(STREAM, Range.unbounded());
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).getValue()).isEqualTo(messages.get(1).getValue());
        assertThat(messages.get(0).getId()).isNotEqualTo(messages.get(1).getId());
    }

    @Test void concurrentRequestsAndTwoInstancesPublishWithoutCorruptingState() throws Exception {
        String key = UUID.randomUUID().toString();
        try (var pool = Executors.newFixedThreadPool(4)) {
            var futures = new ArrayList<Future<UUID>>();
            for (int i = 0; i < 4; i++) futures.add(pool.submit(() -> submit(key)));
            UUID first = futures.getFirst().get(20, TimeUnit.SECONDS);
            for (var future : futures) assertThat(future.get(20, TimeUnit.SECONDS)).isEqualTo(first);
            for (int i = 0; i < 3; i++) submit(UUID.randomUUID().toString());
            try (var second = application(false)) {
                var a = pool.submit(() -> context.getBean(OutboxPublisher.class).publishBatch());
                var b = pool.submit(() -> second.getBean(OutboxPublisher.class).publishBatch());
                a.get(20, TimeUnit.SECONDS); b.get(20, TimeUnit.SECONDS);
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE published_at IS NOT NULL AND attempt_count = 1", Long.class)).isEqualTo(4);
        assertThat(redis.opsForStream().size(STREAM)).isEqualTo(4);
    }

    @Test void retentionIsBoundedAndRetainedOutboxCanReplayTrimmedEvents() throws Exception {
        for (int i = 0; i < 7; i++) submit(UUID.randomUUID().toString());
        publish(); assertThat(redis.opsForStream().size(STREAM)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE published_at IS NOT NULL", Long.class)).isEqualTo(7);
        var event = jdbc.queryForMap("SELECT id FROM outbox_events ORDER BY created_at, id LIMIT 1");
        jdbc.update("UPDATE outbox_events SET published_at = NULL, next_attempt_at = clock_timestamp() WHERE id = ?", event.get("id"));
        publish();
        assertThat(redis.opsForStream().range(STREAM, Range.unbounded()).stream().map(entry -> entry.getValue().get("eventId"))).contains(event.get("id").toString());
        assertThat(redis.opsForStream().size(STREAM)).isEqualTo(5);
    }

    @Test void invalidEventsRetryWithoutLeakingPayloadAndDoNotBlockValidEvents() throws Exception {
        jdbc.update("INSERT INTO outbox_events(id, aggregate_id, event_type, event_version, payload) VALUES (?, ?, 'FUTURE_TYPE', 99, ?::jsonb)", UUID.randomUUID(), UUID.randomUUID(), JSON.writeValueAsString(Map.of("secret", "must-not-publish")));
        submit(UUID.randomUUID().toString()); publish();
        assertThat(redis.opsForStream().size(STREAM)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT last_error_code FROM outbox_events WHERE event_type = 'FUTURE_TYPE'", String.class)).isEqualTo("INVALID_EVENT");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM outbox_events WHERE event_type = 'FUTURE_TYPE'", Integer.class)).isEqualTo(1);
        publish();
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM outbox_events WHERE event_type = 'FUTURE_TYPE'", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("UPDATE outbox_events SET payload = '{}'::jsonb WHERE event_type = 'FUTURE_TYPE'")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void healthSwaggerAndProtectedMetricsExposeUsefulMeasurementsWithoutSecrets() throws Exception {
        submit(UUID.randomUUID().toString()); context.getBean(PlatformMetrics.class).refresh();
        for (String path : List.of("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness", "/actuator/health/dependencies")) {
            var health = body(send("GET", path, null, null), 200); assertThat(health.toString()).doesNotContain("components", "details");
        }
        body(send("GET", "/actuator/metrics", null, null), 401); body(send("GET", "/actuator/prometheus", studentToken, null), 403);
        var metrics = send("GET", "/actuator/prometheus", adminToken, null);
        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.body()).contains("judge_outbox_backlog", "judge_submissions_pending", "judge_submission_acceptance_seconds", "process_cpu_usage", "jvm_memory_used_bytes").doesNotContain(PASSWORD, S3_SECRET, REDIS_PASSWORD);
        assertThat(body(send("GET", "/v3/api-docs", null, null), 200).toString()).contains("/api/submissions/{submissionId}/result", "/api/assignments/{assignmentId}/leaderboard");
    }

    private UUID submit(String key) throws Exception {
        String boundary = "judge" + UUID.randomUUID();
        String multipart = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"programmingLanguage\"\r\n\r\nJAVA\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"Main.java\"\r\nContent-Type: application/octet-stream\r\n\r\nclass Main {}\r\n--" + boundary + "--\r\n";
        var request = HttpRequest.newBuilder(URI.create(base + "/api/assignments/" + assignment + "/submissions"))
                .header("Authorization", "Bearer " + studentToken).header("Idempotency-Key", key).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(multipart)).build();
        return UUID.fromString(body(HTTP.send(request, HttpResponse.BodyHandlers.ofString()), 201).get("submissionId").asText());
    }
    private static void publish() { context.getBean(OutboxPublisher.class).publishBatch(); }
    private static String endpoint() { return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000); }
    private static JsonNode register() throws Exception {
        String name = "u" + UUID.randomUUID().toString().replace("-", "");
        return body(send("POST", "/api/auth/register", null, Map.of("username", name, "email", name + "@example.test", "password", PASSWORD)), 201);
    }
    private static String login(String name) throws Exception { return body(send("POST", "/api/auth/login", null, Map.of("identifier", name, "password", PASSWORD)), 200).get("accessToken").asText(); }
    private static HttpResponse<String> send(String method, String path, String token, Object payload) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json").timeout(Duration.ofSeconds(30));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return HTTP.send(request.method(method, payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload))).build(), HttpResponse.BodyHandlers.ofString());
    }
    private static JsonNode body(HttpResponse<String> response, int status) { assertThat(response.statusCode()).as(response.body()).isEqualTo(status); return JSON.readTree(response.body()); }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(100);
        assertThat(condition.getAsBoolean()).isTrue();
    }
}

