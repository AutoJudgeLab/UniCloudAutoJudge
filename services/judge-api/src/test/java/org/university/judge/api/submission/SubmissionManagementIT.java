package org.university.judge.api.submission;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.university.judge.api.JudgeApiApplication;
import org.university.judge.api.submission.application.SourceCleanupService;
import org.university.judge.api.support.TestJwtKeys;
import org.university.judge.api.user.Role;
import org.university.judge.api.user.User;
import org.university.judge.api.user.UserRepository;
import org.university.judge.storage.ObjectMetadata;
import org.university.judge.storage.ObjectStorageService;
import org.university.judge.storage.PreparedSource;
import org.university.judge.storage.SourceUpload;
import org.university.judge.storage.StorageException;
import org.university.judge.storage.StoredObject;
import org.university.judge.storage.minio.MinioObjectStorageService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
class SubmissionManagementIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withPassword(UUID.randomUUID().toString());
    private static final String S3_ACCESS = "test-" + UUID.randomUUID();
    private static final String S3_SECRET = UUID.randomUUID().toString();
    private static final Path ROOT = Path.of(System.getProperty("basedir", ".")).resolve("../..").normalize().toAbsolutePath();
    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>(new ImageFromDockerfile("online-judge-minio:RELEASE.2025-10-15T17-29-55Z", false)
            .withDockerfile(ROOT.resolve("infrastructure/docker/minio/Dockerfile")))
            .withEnv("MINIO_ROOT_USER", S3_ACCESS).withEnv("MINIO_ROOT_PASSWORD", S3_SECRET)
            .withCommand("server", "/data").withExposedPorts(9000).waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));
    private static final String PASSWORD = "Submissions9!" + UUID.randomUUID();
    private static final byte[] SOURCE = "class Main { public static void main(String[] args) {} }\n".getBytes(StandardCharsets.UTF_8);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static UserRepository users;
    private static String hash;
    private static String base;
    private static String teacherToken;
    private static String outsiderTeacherToken;
    private static String adminToken;
    private static FaultStorage storage;
    private User student;
    private String token;
    private UUID assignment;

    @BeforeAll
    static void start() throws Exception {
        String endpoint = "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
        MinioClient.builder().endpoint(endpoint).credentials(S3_ACCESS, S3_SECRET).build()
                .makeBucket(MakeBucketArgs.builder().bucket("submissions-test").build());
        context = new SpringApplicationBuilder(JudgeApiApplication.class, FaultConfiguration.class).run(TestJwtKeys.arguments(
                "--server.port=0", "--spring.datasource.url=" + POSTGRES.getJdbcUrl(), "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(), "--management.health.redis.enabled=false", "--spring.data.redis.password=unused",
                "--management.endpoint.health.group.readiness.include=readinessState,db", "--storage.s3.endpoint=" + endpoint,
                "--storage.s3.access-key=" + S3_ACCESS, "--storage.s3.secret-key=" + S3_SECRET, "--storage.s3.bucket=submissions-test",
                "--storage.s3.max-upload-size=1KB", "--submission.cleanup.enabled=false"));
        jdbc = context.getBean(JdbcTemplate.class);
        users = context.getBean(UserRepository.class);
        storage = context.getBean(FaultStorage.class);
        hash = context.getBean(PasswordEncoder.class).encode(PASSWORD);
        base = "http://localhost:" + context.getEnvironment().getRequiredProperty("local.server.port");
        teacherToken = login(account(Role.TEACHER));
        outsiderTeacherToken = login(account(Role.TEACHER));
        adminToken = login(account(Role.ADMIN));
    }

    @BeforeEach
    void setup() throws Exception {
        storage.reset();
        student = account(Role.STUDENT);
        token = login(student);
        assignment = assignment(Instant.now().plus(1, ChronoUnit.DAYS), true);
    }

    @AfterAll
    static void stop() { if (context != null) context.close(); }

    @Test
    void acceptsPendingSubmissionOnlyAfterSourceAndOutboxAreStored() throws Exception {
        Instant before = Instant.now().minusSeconds(1);
        var response = submit(assignment, token, key(), "JAVA", "Main.java", SOURCE);
        JsonNode accepted = body(response, 201);
        assertThat(accepted.size()).isEqualTo(4);
        UUID id = submissionId(accepted);
        assertThat(response.headers().firstValue("Location")).contains("/api/submissions/" + id);
        assertThat(accepted.get("assignmentId").asText()).isEqualTo(assignment.toString());
        assertThat(accepted.get("status").asText()).isEqualTo("PENDING");
        assertThat(Instant.parse(accepted.get("submittedAt").asText())).isBetween(before, Instant.now());
        var row = jdbc.queryForMap("SELECT * FROM submissions WHERE id = ?", id);
        assertThat(row.get("student_id")).isEqualTo(student.getId());
        assertThat(row.get("source_size")).isEqualTo((long) SOURCE.length);
        String objectKey = (String) row.get("source_file_key");
        assertThat(objectKey).startsWith("submissions/" + id + "/source/");
        try (var download = storage.download(objectKey)) { assertThat(download.content().readAllBytes()).isEqualTo(SOURCE); }
        var event = jdbc.queryForMap("SELECT * FROM outbox_events WHERE aggregate_id = ? AND event_type = 'SUBMISSION_CREATED'", id);
        assertThat(event.get("event_version")).isEqualTo(1);
        assertThat(event.get("published_at")).isNull();
        JsonNode payload = JSON.readTree(event.get("payload").toString());
        assertThat(payload.get("submissionId").asText()).isEqualTo(id.toString());
        assertThat(payload.get("assignmentRevisionId").asText()).isEqualTo(row.get("assignment_revision_id").toString());
        assertThat(payload.get("sourceSha256").asText()).isEqualTo(row.get("source_sha256"));
        assertThat(payload.get("sourceSize").asLong()).isEqualTo(SOURCE.length);
        assertThat(count("source_upload_cleanup", "request_id", id)).isZero();
        JsonNode detail = body(send("GET", "/api/submissions/" + id, token, null), 200);
        assertThat(detail.get("sourceSize").asLong()).isEqualTo(SOURCE.length);
        assertThat(detail.toString()).doesNotContain(objectKey, "sourceSha256", PASSWORD, S3_SECRET, "expectedOutput");
        assertThatThrownBy(() -> jdbc.update("UPDATE submissions SET source_size = source_size + 1 WHERE id = ?", id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE submissions SET source_sha256 = repeat('0', 64) WHERE id = ?", id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void rejectsMissingDraftArchivedExpiredAndUnsealedAssignments() throws Exception {
        problem(submit(UUID.randomUUID(), token, key(), "JAVA", "Main.java", SOURCE), 404, "ASSIGNMENT_NOT_FOUND");
        UUID draft = assignment(Instant.now().plusSeconds(60), false);
        problem(submit(draft, token, key(), "JAVA", "Main.java", SOURCE), 409, "ASSIGNMENT_NOT_PUBLISHED");
        body(send("POST", "/api/assignments/" + assignment + "/archive", teacherToken, null), 200);
        problem(submit(assignment, token, key(), "JAVA", "Main.java", SOURCE), 409, "ASSIGNMENT_NOT_PUBLISHED");
        UUID expired = assignment(Instant.now().plusSeconds(2), true);
        awaitDeadline(expired);
        problem(submit(expired, token, key(), "JAVA", "Main.java", SOURCE), 409, "DEADLINE_PASSED");
        jdbc.update("UPDATE assignments SET status = 'PUBLISHED' WHERE id = ?", draft);
        problem(submit(draft, token, key(), "JAVA", "Main.java", SOURCE), 409, "GRADING_NOT_READY");
        assertThat(storage.uploads).isEmpty();
    }

    @Test
    void validatesMultipartLanguageFilenameContentAndSizeWithoutTrustingMime() throws Exception {
        problem(submit(assignment, token, key(), "BASH", "Main.java", SOURCE), 400, "UNSUPPORTED_LANGUAGE");
        problem(submit(assignment, token, key(), "PYTHON", "Main.java", SOURCE), 400, "LANGUAGE_FILE_MISMATCH");
        problem(submit(assignment, token, key(), "JAVA", "Main.java", new byte[0]), 400, "INVALID_UPLOAD");
        problem(submit(assignment, token, key(), "JAVA", "Main.java", new byte[]{0, 1, 2}), 400, "INVALID_UPLOAD");
        problem(submit(assignment, token, key(), "JAVA", "../Main.java", SOURCE), 400, "INVALID_UPLOAD");
        byte[] large = new byte[1025]; java.util.Arrays.fill(large, (byte) 'a');
        problem(submit(assignment, token, key(), "JAVA", "Main.java", large), 413, "UPLOAD_TOO_LARGE");
        byte[] servletLimit = new byte[1024 * 1024 + 1]; java.util.Arrays.fill(servletLimit, (byte) 'a');
        problem(submit(assignment, token, key(), "JAVA", "Main.java", servletLimit), 413, "UPLOAD_TOO_LARGE");
        problem(submit(assignment, token, null, "JAVA", "Main.java", SOURCE), 400, "INVALID_SUBMISSION_REQUEST");
        problem(submit(assignment, token, "bad key", "JAVA", "Main.java", SOURCE), 400, "INVALID_SUBMISSION_REQUEST");
        problem(multipart(assignment, token, key(), "JAVA", "Main.java", SOURCE, "studentId", student.getId().toString()), 400, "INVALID_SUBMISSION_REQUEST");
        problem(multipart(assignment, token, key(), "JAVA", "Main.java", SOURCE, "submittedAt", "2000-01-01T00:00:00Z"), 400, "INVALID_SUBMISSION_REQUEST");
        problem(multipart(assignment, token, key(), "JAVA", "Main.java", SOURCE, "programmingLanguage", "PYTHON"), 400, "INVALID_SUBMISSION_REQUEST");
        assertThat(storage.uploads).isEmpty();
        // The multipart helper always claims application/octet-stream; content is validated independently.
        body(submit(assignment, token, key(), "JAVA", "Main.java", SOURCE), 201);
    }

    @Test
    void authenticationAndRolesAreRequiredForSubmission() throws Exception {
        problem(submit(assignment, null, key(), "JAVA", "Main.java", SOURCE), 401, "UNAUTHORIZED");
        problem(submit(assignment, "invalid.jwt.token", key(), "JAVA", "Main.java", SOURCE), 401, "UNAUTHORIZED");
        for (String forbidden : List.of(teacherToken, adminToken)) problem(submit(assignment, forbidden, key(), "JAVA", "Main.java", SOURCE), 403, "FORBIDDEN");
        assertThat(storage.uploads).isEmpty();
    }

    @Test
    void readsEnforceStudentAndTeacherOwnershipAndRetainHistoricalAccess() throws Exception {
        UUID id = submissionId(body(submit(assignment, token, key(), "JAVA", "Main.java", SOURCE), 201));
        String otherStudent = login(account(Role.STUDENT));
        problem(send("GET", "/api/submissions/" + id, null, null), 401, "UNAUTHORIZED");
        problem(send("GET", "/api/submissions/" + id, otherStudent, null), 404, "SUBMISSION_NOT_FOUND");
        problem(send("GET", "/api/submissions/" + id, outsiderTeacherToken, null), 404, "SUBMISSION_NOT_FOUND");
        body(send("GET", "/api/submissions/" + id, teacherToken, null), 200);
        body(send("GET", "/api/submissions/" + id, adminToken, null), 200);
        assertThat(body(send("GET", listPath(), otherStudent, null), 200).get("totalElements").asLong()).isZero();
        problem(send("GET", listPath(), outsiderTeacherToken, null), 404, "ASSIGNMENT_NOT_FOUND");
        body(send("POST", "/api/assignments/" + assignment + "/archive", teacherToken, null), 200);
        assertThat(body(send("GET", listPath(), token, null), 200).get("totalElements").asLong()).isEqualTo(1);
        body(send("GET", "/api/submissions/" + id, token, null), 200);
        problem(send("GET", listPath(), otherStudent, null), 404, "ASSIGNMENT_NOT_FOUND");
    }

    @Test
    void paginationFilteringAndSortingRemainScopedToTheCaller() throws Exception {
        for (int i = 0; i < 3; i++) body(submit(assignment, token, key(), "JAVA", "Main.java", SOURCE), 201);
        body(submit(assignment, token, key(), "PYTHON", "main.py", "print(1)".getBytes(StandardCharsets.UTF_8)), 201);
        String other = login(account(Role.STUDENT));
        body(submit(assignment, other, key(), "JAVA", "Main.java", SOURCE), 201);
        String query = "?size=2&sort=submittedAt,asc&programmingLanguage=JAVA&status=PENDING";
        JsonNode first = body(send("GET", "/api/users/me/submissions" + query, token, null), 200);
        assertThat(first.get("totalElements").asLong()).isEqualTo(3);
        assertThat(first.get("totalPages").asInt()).isEqualTo(2);
        JsonNode second = body(send("GET", "/api/users/me/submissions" + query + "&page=1", token, null), 200);
        assertThat(second.get("content").size()).isEqualTo(1);
        assertThat(first.at("/content/0/submissionId")).isNotEqualTo(second.at("/content/0/submissionId"));
        assertThat(body(send("GET", listPath(), teacherToken, null), 200).get("totalElements").asLong()).isEqualTo(5);
        assertThat(body(send("GET", listPath(), adminToken, null), 200).get("totalElements").asLong()).isEqualTo(5);
        assertThat(body(send("GET", listPath() + "?assignmentId=" + UUID.randomUUID(), token, null), 200).get("totalElements").asLong()).isZero();
        for (String bad : List.of("page=-1", "size=101", "sort=sourceFileKey,asc", "status=UNKNOWN", "programmingLanguage=BASH")) {
            body(send("GET", "/api/users/me/submissions?" + bad, token, null), 400);
        }
    }

    @Test
    void equivalentRetriesReturnOriginalWhileConflictingContentFilenameOrAssignmentFail() throws Exception {
        String key = key();
        JsonNode first = body(submit(assignment, token, key, "JAVA", "Main.java", SOURCE), 201);
        assertThat(body(submit(assignment, token, key, "JAVA", "Main.java", SOURCE), 201)).isEqualTo(first);
        problem(submit(assignment, token, key, "JAVA", "Other.java", SOURCE), 409, "IDEMPOTENCY_CONFLICT");
        problem(submit(assignment, token, key, "JAVA", "Main.java", "class Other {}".getBytes(StandardCharsets.UTF_8)), 409, "IDEMPOTENCY_CONFLICT");
        problem(submit(UUID.randomUUID(), token, key, "JAVA", "Main.java", SOURCE), 409, "IDEMPOTENCY_CONFLICT");
        body(send("POST", "/api/assignments/" + assignment + "/archive", teacherToken, null), 200);
        assertThat(body(submit(assignment, token, key, "JAVA", "Main.java", SOURCE), 201)).isEqualTo(first);
        assertThat(storage.uploads).hasSize(1);
        UUID id = submissionId(first);
        assertThat(count("submissions", "id", id)).isEqualTo(1);
        assertThat(count("outbox_events", "aggregate_id", id)).isEqualTo(1);
    }

    @Test
    void concurrentSameKeyAndDifferentKeysCreateExactlyTheExpectedSubmissions() throws Exception {
        String idempotency = key();
        var same = concurrently(() -> submit(assignment, token, idempotency, "JAVA", "Main.java", SOURCE),
                () -> submit(assignment, token, idempotency, "JAVA", "Main.java", SOURCE));
        assertThat(body(same.get(0), 201)).isEqualTo(body(same.get(1), 201));
        assertThat(storage.uploads).hasSize(1);
        var distinct = concurrently(() -> submit(assignment, token, key(), "JAVA", "Main.java", SOURCE),
                () -> submit(assignment, token, key(), "JAVA", "Main.java", SOURCE));
        assertThat(submissionId(body(distinct.get(0), 201))).isNotEqualTo(submissionId(body(distinct.get(1), 201)));
        assertThat(count("submissions", "assignment_id", assignment)).isEqualTo(3);
        assertThat(storage.uploads).hasSize(3);
    }

    @Test
    void sameKeyIsIndependentAcrossStudents() throws Exception {
        String idempotency = key();
        UUID first = submissionId(body(submit(assignment, token, idempotency, "JAVA", "Main.java", SOURCE), 201));
        String other = login(account(Role.STUDENT));
        UUID second = submissionId(body(submit(assignment, other, idempotency, "JAVA", "Main.java", SOURCE), 201));
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void concurrentConflictingKeyCannotCreateTwoSubmissions() throws Exception {
        String idempotency = key();
        var responses = concurrently(() -> submit(assignment, token, idempotency, "JAVA", "Main.java", SOURCE),
                () -> submit(assignment, token, idempotency, "JAVA", "Main.java", "class Other {}".getBytes(StandardCharsets.UTF_8)));
        assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(201, 409);
        assertThat(count("submissions", "assignment_id", assignment)).isEqualTo(1);
        assertThat(storage.uploads).hasSize(1);
    }

    @Test
    void republishedGradingRevisionDuringUploadRequiresRetry() throws Exception {
        storage.afterUpload = () -> {
            try {
                body(send("PUT", "/api/assignments/" + assignment + "?createNewRevision=true", teacherToken,
                        Map.of("title", "Revised assignment", "description", "New instructions", "maximumScore", 100,
                                "deadline", Instant.now().plus(1, ChronoUnit.DAYS).toString())), 200);
                body(send("POST", "/api/assignments/" + assignment + "/publish", teacherToken, null), 200);
            } catch (Exception error) { throw new IllegalStateException(error); }
        };
        String idempotency = key();
        problem(submit(assignment, token, idempotency, "JAVA", "Main.java", SOURCE), 409, "GRADING_REVISION_CHANGED");
        assertThat(count("submissions", "assignment_id", assignment)).isZero();
        storage.afterUpload = () -> { };
        UUID id = submissionId(body(submit(assignment, token, idempotency, "JAVA", "Main.java", SOURCE), 201));
        UUID current = jdbc.queryForObject("SELECT id FROM assignment_revisions WHERE assignment_id = ? ORDER BY revision_number DESC LIMIT 1", UUID.class, assignment);
        assertThat(jdbc.queryForObject("SELECT assignment_revision_id FROM submissions WHERE id = ?", UUID.class, id)).isEqualTo(current);
    }

    @Test
    void uploadFinishingAfterDeadlineIsNotAcceptedAndSourceIsCleaned() throws Exception {
        assignment = assignment(Instant.now().plusSeconds(3), true);
        storage.afterUpload = () -> awaitDeadline(assignment);
        problem(submit(assignment, token, key(), "JAVA", "Main.java", SOURCE), 409, "DEADLINE_PASSED");
        assertThat(count("submissions", "assignment_id", assignment)).isZero();
        assertThat(storage.uploads).hasSize(1);
        assertThat(storage.exists(storage.uploads.getFirst())).isFalse();
    }

    @Test
    void rechecksAccountAndPublishedGradingAfterUpload() throws Exception {
        storage.afterUpload = () -> jdbc.update("UPDATE users SET enabled = false WHERE id = ?", student.getId());
        problem(submit(assignment, token, key(), "JAVA", "Main.java", SOURCE), 401, "UNAUTHORIZED");
        assertThat(count("submissions", "assignment_id", assignment)).isZero();
        assertThat(storage.exists(storage.uploads.getFirst())).isFalse();
        jdbc.update("UPDATE users SET enabled = true WHERE id = ?", student.getId());
        token = login(student);
        storage.afterUpload = () -> {
            try { body(send("POST", "/api/assignments/" + assignment + "/archive", teacherToken, null), 200); }
            catch (Exception error) { throw new IllegalStateException(error); }
        };
        problem(submit(assignment, token, key(), "JAVA", "Main.java", SOURCE), 409, "ASSIGNMENT_NOT_PUBLISHED");
        assertThat(count("submissions", "assignment_id", assignment)).isZero();
    }

    @Test
    void minioFailureAndLostPutAcknowledgementLeaveRetryableCleanupIntents() throws Exception {
        String idempotency = key();
        storage.failUpload = true;
        problem(submit(assignment, token, idempotency, "JAVA", "Main.java", SOURCE), 503, "STORAGE_UNAVAILABLE");
        assertThat(count("submissions", "assignment_id", assignment)).isZero();
        storage.failUpload = false;
        storage.failAfterUpload = true;
        storage.failDelete = true;
        problem(submit(assignment, token, idempotency, "JAVA", "Main.java", SOURCE), 503, "STORAGE_UNAVAILABLE");
        String orphan = storage.uploads.getFirst();
        assertThat(storage.exists(orphan)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_upload_cleanup WHERE object_key = ?", Long.class, orphan)).isEqualTo(1);
        storage.failAfterUpload = false;
        storage.failDelete = false;
        body(submit(assignment, token, idempotency, "JAVA", "Main.java", SOURCE), 201);
        reconcileAll();
        assertThat(storage.exists(orphan)).isFalse();
        assertThat(storage.exists(storage.uploads.getLast())).isTrue();
        assertThat(count("submissions", "assignment_id", assignment)).isEqualTo(1);
        // Simulate a PUT completing after an earlier missing-object cleanup. Its tombstone survives.
        try (var source = storage.prepare("Main.java", SOURCE.length, new ByteArrayInputStream(SOURCE))) { storage.delegate.upload(orphan, source); }
        assertThat(storage.exists(orphan)).isTrue();
        reconcileAll();
        assertThat(storage.exists(orphan)).isFalse();
    }

    @Test
    void postgresOutboxFailureRollsBackSubmissionAndRetryCreatesExactlyOneEvent() throws Exception {
        jdbc.execute("CREATE FUNCTION reject_test_event() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.event_type = 'SUBMISSION_CREATED' THEN RAISE EXCEPTION 'test failure' USING ERRCODE = '23514'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER reject_test_event BEFORE INSERT ON outbox_events FOR EACH ROW EXECUTE FUNCTION reject_test_event()");
        String idempotency = key();
        try {
            problem(submit(assignment, token, idempotency, "JAVA", "Main.java", SOURCE), 503, "SUBMISSION_UNAVAILABLE");
            assertThat(count("submissions", "assignment_id", assignment)).isZero();
            assertThat(storage.exists(storage.uploads.getFirst())).isFalse();
        } finally {
            jdbc.execute("DROP TRIGGER reject_test_event ON outbox_events");
            jdbc.execute("DROP FUNCTION reject_test_event()");
        }
        UUID id = submissionId(body(submit(assignment, token, idempotency, "JAVA", "Main.java", SOURCE), 201));
        assertThat(count("outbox_events", "aggregate_id", id)).isEqualTo(1);
        assertThat(storage.uploads).hasSize(2);
    }

    @Test
    void reconciliationNeverDeletesCommittedOrCurrentlyUploadingSources() throws Exception {
        CountDownLatch uploaded = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        storage.afterUpload = () -> {
            uploaded.countDown();
            try { assertThat(release.await(10, TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
        };
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> submit(assignment, token, key(), "JAVA", "Main.java", SOURCE));
            try {
                assertThat(uploaded.await(10, TimeUnit.SECONDS)).isTrue();
                String key = storage.uploads.getFirst();
                reconcileAll();
                assertThat(storage.exists(key)).isTrue();
            } finally { release.countDown(); }
            UUID id = submissionId(body(future.get(15, TimeUnit.SECONDS), 201));
            String key = storage.uploads.getFirst();
            jdbc.update("INSERT INTO source_upload_cleanup(object_key, request_id, next_attempt_at) VALUES (?, ?, now())", key, id);
            reconcileAll();
            assertThat(storage.exists(key)).isTrue();
            assertThat(count("source_upload_cleanup", "request_id", id)).isZero();
        }
    }

    @Test
    void openApiDocumentsMultipartIdempotencyAndPrivateQueries() throws Exception {
        JsonNode docs = body(send("GET", "/v3/api-docs", null, null), 200);
        JsonNode operation = docs.get("paths").get("/api/assignments/{assignmentId}/submissions").get("post");
        assertThat(operation.get("requestBody").get("content").has("multipart/form-data")).isTrue();
        JsonNode multipartSchema = operation.at("/requestBody/content/multipart~1form-data/schema");
        if (multipartSchema.has("$ref")) multipartSchema = docs.at(multipartSchema.get("$ref").asText().substring(1));
        assertThat(multipartSchema.get("properties").has("file")).isTrue();
        assertThat(multipartSchema.get("properties").has("programmingLanguage")).isTrue();
        assertThat(operation.get("parameters").toString()).contains("Idempotency-Key");
        assertThat(operation.get("security").toString()).contains("bearerAuth");
        assertThat(docs.at("/components/schemas/SubmissionCreatedResponse/properties").size()).isEqualTo(4);
        assertThat(docs.at("/components/schemas/SubmissionResponse/properties").has("sourceFileKey")).isFalse();
    }

    private static UUID assignment(Instant deadline, boolean publish) throws Exception {
        UUID id = UUID.fromString(body(send("POST", "/api/assignments", teacherToken, Map.of("title", "Submission assignment", "description", "Instructions",
                "maximumScore", 100, "deadline", deadline.truncatedTo(ChronoUnit.MICROS).toString())), 201).get("id").asText());
        body(send("POST", "/api/assignments/" + id + "/test-cases", teacherToken, Map.of("inputData", "1", "expectedOutput", "1", "points", 100, "isHidden", true, "executionOrder", 0)), 201);
        if (publish) body(send("POST", "/api/assignments/" + id + "/publish", teacherToken, null), 200);
        return id;
    }
    private static void awaitDeadline(UUID assignmentId) {
        Instant deadline = jdbc.queryForObject("SELECT deadline FROM assignments WHERE id = ?", java.time.OffsetDateTime.class, assignmentId).toInstant();
        long millis = Duration.between(Instant.now(), deadline.plusMillis(50)).toMillis();
        if (millis > 0) try { Thread.sleep(millis); } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
    }
    private static void reconcileAll() {
        jdbc.update("UPDATE source_upload_cleanup SET next_attempt_at = now()");
        context.getBean(SourceCleanupService.class).reconcileBatch();
    }
    private static long count(String table, String column, UUID id) { return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Long.class, id); }
    private String listPath() { return "/api/assignments/" + assignment + "/submissions"; }
    private static String key() { return UUID.randomUUID().toString(); }
    private static UUID submissionId(JsonNode body) { return UUID.fromString(body.get("submissionId").asText()); }
    private static User account(Role role) {
        String name = "submission-" + UUID.randomUUID().toString().substring(0, 12);
        return users.saveAndFlush(new User(name, name + "@example.test", hash, role));
    }
    private static String login(User user) throws Exception {
        return body(send("POST", "/api/auth/login", null, Map.of("identifier", user.getUsername(), "password", PASSWORD)), 200).get("accessToken").asText();
    }
    private static HttpResponse<String> submit(UUID id, String token, String key, String language, String filename, byte[] content) throws Exception {
        return multipart(id, token, key, language, filename, content, null, null);
    }
    private static HttpResponse<String> multipart(UUID id, String token, String key, String language, String filename, byte[] content, String extraName, String extraValue) throws Exception {
        String boundary = "boundary-" + UUID.randomUUID();
        var bytes = new ByteArrayOutputStream();
        bytes.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"programmingLanguage\"\r\n\r\n" + language + "\r\n").getBytes(StandardCharsets.UTF_8));
        bytes.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        bytes.write(content);
        bytes.write("\r\n".getBytes(StandardCharsets.UTF_8));
        if (extraName != null) bytes.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + extraName + "\"\r\n\r\n" + extraValue + "\r\n").getBytes(StandardCharsets.UTF_8));
        bytes.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(URI.create(base + "/api/assignments/" + id + "/submissions")).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray()));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (key != null) request.header("Idempotency-Key", key);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private static HttpResponse<String> send(String method, String path, String token, Object payload) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (payload != null) request.header("Content-Type", "application/json");
        request.method(method, payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload)));
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private static JsonNode body(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return JSON.readTree(response.body());
    }
    private static void problem(HttpResponse<String> response, int status, String code) {
        assertThat(body(response, status).get("code").asText()).isEqualTo(code);
        assertThat(response.headers().firstValue("content-type").orElse("")).contains("application/problem+json");
        assertThat(response.body()).doesNotContain(PASSWORD, S3_SECRET);
    }
    private static List<HttpResponse<String>> concurrently(Callable<HttpResponse<String>> a, Callable<HttpResponse<String>> b) throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return a.call(); });
            var second = executor.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return b.call(); });
            return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        }
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class FaultConfiguration {
        @Bean @Primary
        FaultStorage faultStorage(@Qualifier("objectStorageService") MinioObjectStorageService delegate) { return new FaultStorage(delegate); }
    }
    static class FaultStorage implements ObjectStorageService {
        final ObjectStorageService delegate;
        final List<String> uploads = new CopyOnWriteArrayList<>();
        volatile boolean failUpload, failAfterUpload, failDelete;
        volatile Runnable afterUpload = () -> { };
        FaultStorage(ObjectStorageService delegate) { this.delegate = delegate; }
        void reset() { uploads.clear(); failUpload = false; failAfterUpload = false; failDelete = false; afterUpload = () -> { }; }
        @Override public PreparedSource prepare(String name, long size, InputStream content) { return delegate.prepare(name, size, content); }
        @Override public String newSourceKey(UUID id, PreparedSource source) { return delegate.newSourceKey(id, source); }
        @Override public ObjectMetadata upload(String key, PreparedSource source) {
            if (failUpload) throw new StorageException(StorageException.Code.STORAGE_UNAVAILABLE);
            ObjectMetadata metadata = delegate.upload(key, source);
            uploads.add(key);
            afterUpload.run();
            if (failAfterUpload) throw new StorageException(StorageException.Code.STORAGE_UNAVAILABLE);
            return metadata;
        }
        @Override public ObjectMetadata upload(SourceUpload upload) { return delegate.upload(upload); }
        @Override public StoredObject download(String key) { return delegate.download(key); }
        @Override public ObjectMetadata getMetadata(String key) { return delegate.getMetadata(key); }
        @Override public boolean exists(String key) { return delegate.exists(key); }
        @Override public void delete(String key) {
            if (failDelete) throw new StorageException(StorageException.Code.STORAGE_UNAVAILABLE);
            delegate.delete(key);
        }
    }
}
