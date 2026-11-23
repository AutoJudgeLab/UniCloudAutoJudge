package org.university.judge.api.assignment;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.university.judge.api.JudgeApiApplication;
import org.university.judge.api.assignment.api.AssignmentRequest;
import org.university.judge.api.assignment.application.AssignmentService;
import org.university.judge.api.assignment.domain.Assignment;
import org.university.judge.api.assignment.domain.AssignmentRevision;
import org.university.judge.api.assignment.persistence.AssignmentRepository;
import org.university.judge.api.assignment.persistence.AssignmentRevisionRepository;
import org.university.judge.api.result.SubmissionResult;
import org.university.judge.api.result.SubmissionResultRepository;
import org.university.judge.api.result.TestCaseResult;
import org.university.judge.api.result.TestCaseResultRepository;
import org.university.judge.api.result.Verdict;
import org.university.judge.api.security.AuthenticatedUser;
import org.university.judge.api.submission.domain.Submission;
import org.university.judge.api.submission.persistence.SubmissionRepository;
import org.university.judge.api.support.TestJwtKeys;
import org.university.judge.api.testcase.domain.TestCase;
import org.university.judge.api.testcase.persistence.TestCaseRepository;
import org.university.judge.api.user.Role;
import org.university.judge.api.user.User;
import org.university.judge.api.user.UserRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class AssignmentManagementIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withPassword(UUID.randomUUID().toString());
    private static final String PASSWORD = "Assignments9!" + UUID.randomUUID();
    private static final String DEADLINE = Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static ConfigurableApplicationContext context;
    private static UserRepository users;
    private static AssignmentRepository assignments;
    private static AssignmentRevisionRepository revisions;
    private static TestCaseRepository cases;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static String base;
    private static String passwordHash;
    private static String adminToken;
    private static String studentToken;
    private static User student;
    private User teacher;
    private String teacherToken;

    @BeforeAll
    static void startApplication() throws Exception {
        context = new SpringApplicationBuilder(JudgeApiApplication.class).run(TestJwtKeys.arguments(
                "--server.port=0", "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(), "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.data.redis.password=unused", "--management.health.redis.enabled=false",
                "--management.endpoint.health.group.readiness.include=readinessState,db"));
        users = context.getBean(UserRepository.class);
        assignments = context.getBean(AssignmentRepository.class);
        revisions = context.getBean(AssignmentRevisionRepository.class);
        cases = context.getBean(TestCaseRepository.class);
        jdbc = context.getBean(JdbcTemplate.class);
        tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        base = "http://localhost:" + context.getEnvironment().getRequiredProperty("local.server.port");
        passwordHash = context.getBean(PasswordEncoder.class).encode(PASSWORD);
        adminToken = login(account(Role.ADMIN));
        student = account(Role.STUDENT);
        studentToken = login(student);
    }

    @BeforeEach
    void isolatedOwner() throws Exception {
        teacher = account(Role.TEACHER);
        teacherToken = login(teacher);
    }

    @AfterAll
    static void stopApplication() { if (context != null) context.close(); }

    @Test
    void createAndUpdateDraftReturnOnlyPublicMetadataAndNumberedRevisions() throws Exception {
        var response = send("POST", "/api/assignments", teacherToken, metadata("  First assignment  "));
        JsonNode created = body(response, 201);
        assertMetadata(created);
        UUID id = UUID.fromString(created.get("id").asText());
        assertThat(response.headers().firstValue("Location")).contains(path(id));
        assertRevision(response, 1);
        assertThat(created.get("title").asText()).isEqualTo("First assignment");
        assertThat(created.get("status").asText()).isEqualTo("DRAFT");
        assertThat(created.get("teacherId").asText()).isEqualTo(teacher.getId().toString());
        var updatedResponse = send("PUT", path(id), teacherToken, metadata("Updated assignment"));
        JsonNode updated = body(updatedResponse, 200);
        assertMetadata(updated);
        assertRevision(updatedResponse, 2);
        assertThat(updated.get("createdAt")).isEqualTo(created.get("createdAt"));
        assertThat(Instant.parse(updated.get("updatedAt").asText())).isAfterOrEqualTo(Instant.parse(created.get("updatedAt").asText()));
        assertThat(revisions.findFirstByAssignmentIdOrderByRevisionNumberDesc(id).orElseThrow().getTitle()).isEqualTo("Updated assignment");
        var unchanged = send("PUT", path(id), teacherToken, metadata("Updated assignment"));
        body(unchanged, 200);
        assertRevision(unchanged, 2);
        assertThat(jdbc.queryForObject("SELECT title FROM assignment_revisions WHERE assignment_id = ? AND revision_number = 1", String.class, id))
                .isEqualTo("First assignment");
    }

    @Test
    void rejectsInvalidMetadataAndClientControlledOwnershipOrStatus() throws Exception {
        UUID id = create("Validation");
        for (var invalid : List.of(Map.entry("title", " "), Map.entry("title", "a".repeat(256)),
                Map.entry("description", "\n "), Map.entry("maximumScore", "0"), Map.entry("maximumScore", "-1"),
                Map.entry("maximumScore", "1.00001"), Map.entry("maximumScore", "1000000000000000"),
                Map.entry("deadline", "2020-01-01T00:00:00Z"), Map.entry("deadline", "invalid"),
                Map.entry("teacherId", UUID.randomUUID().toString()), Map.entry("status", "PUBLISHED"))) {
            var payload = metadata("Validation");
            payload.put(invalid.getKey(), invalid.getValue());
            body(send("POST", "/api/assignments", teacherToken, payload), 400);
            body(send("PUT", path(id), teacherToken, payload), 400);
        }
        for (String required : List.of("title", "description", "maximumScore", "deadline")) {
            var payload = metadata("Missing field");
            payload.remove(required);
            body(send("POST", "/api/assignments", teacherToken, payload), 400);
        }
        assertThat(assignments.findById(id).orElseThrow().getTitle()).isEqualTo("Validation");
    }

    @Test
    void publicationRequiresTestsAndSealsGradingBeforeStudentVisibility() throws Exception {
        UUID id = create("Discovery");
        problem(send("GET", path(id), studentToken, null), 404, "ASSIGNMENT_NOT_FOUND");
        problem(send("POST", path(id) + "/publish", teacherToken, null), 409, "TEST_CASES_REQUIRED");
        addCase(id, false);
        var published = send("POST", path(id) + "/publish", teacherToken, null);
        assertThat(body(published, 200).get("status").asText()).isEqualTo("PUBLISHED");
        assertRevision(published, 1);
        assertMetadata(body(send("GET", path(id), studentToken, null), 200));
        body(send("POST", path(id) + "/publish", teacherToken, null), 200);
        AssignmentRevision revision = latest(id);
        assertThat(revision.getSealedAt()).isNotNull();
        assertThat(cases.findByRevisionIdOrderByExecutionOrder(revision.getId())).hasSize(1);
        JsonNode listing = body(send("GET", "/api/assignments?size=100", studentToken, null), 200);
        assertThat(listing.get("totalElements").asLong()).isPositive();
        for (JsonNode assignment : listing.get("content")) assertThat(assignment.get("status").asText()).isEqualTo("PUBLISHED");
        for (String status : List.of("DRAFT", "ARCHIVED")) {
            assertThat(body(send("GET", "/api/assignments?status=" + status, studentToken, null), 200).get("totalElements").asLong()).isZero();
        }
    }

    @Test
    void everyMutationEnforcesOwnershipAndRolesAndAllRoutesRequireAuthentication() throws Exception {
        UUID id = create("Owned");
        String outsider = login(account(Role.TEACHER));
        List<Route> writes = List.of(new Route("POST", "/api/assignments", metadata("Unauthorized")),
                new Route("PUT", path(id), metadata("Unauthorized")), new Route("DELETE", path(id), null),
                new Route("POST", path(id) + "/publish", null), new Route("POST", path(id) + "/archive", null));
        for (Route route : writes) {
            problem(send(route.method(), route.path(), null, route.payload()), 401, "UNAUTHORIZED");
            problem(send(route.method(), route.path(), studentToken, route.payload()), 403, "FORBIDDEN");
            if (!route.path().equals("/api/assignments")) {
                problem(send(route.method(), route.path(), outsider, route.payload()), 403, "FORBIDDEN");
            }
        }
        problem(send("GET", "/api/assignments", null, null), 401, "UNAUTHORIZED");
        problem(send("GET", path(id), null, null), 401, "UNAUTHORIZED");
        problem(send("GET", path(id), outsider, null), 404, "ASSIGNMENT_NOT_FOUND");
        assertThat(assignments.findById(id).orElseThrow().getTitle()).isEqualTo("Owned");
    }

    @Test
    void administratorCanManageAnyOwnerWithoutReassigningOwnership() throws Exception {
        UUID id = create("Admin managed");
        addCase(id, false);
        body(send("GET", path(id), adminToken, null), 200);
        JsonNode updated = body(send("PUT", path(id), adminToken, metadata("Admin edit")), 200);
        assertThat(updated.get("teacherId").asText()).isEqualTo(teacher.getId().toString());
        body(send("POST", path(id) + "/publish", adminToken, null), 200);
        body(send("POST", path(id) + "/archive", adminToken, null), 200);
        UUID removable = create("Removable");
        assertThat(send("DELETE", path(removable), adminToken, null).statusCode()).isEqualTo(204);
        JsonNode adminCreated = body(send("POST", "/api/assignments", adminToken, metadata("Admin created")), 201);
        JsonNode me = body(send("GET", "/api/users/me", adminToken, null), 200);
        assertThat(adminCreated.get("teacherId")).isEqualTo(me.get("id"));
        assertThat(body(send("GET", "/api/assignments", adminToken, null), 200).get("totalElements").asLong()).isEqualTo(assignments.count());
    }

    @Test
    void listingScopesPaginationAndStableSortingAndRejectsInvalidParameters() throws Exception {
        UUID gamma = create("Gamma");
        UUID alpha = create("Alpha");
        UUID beta = create("Beta");
        addCase(beta, false);
        body(send("POST", path(beta) + "/publish", teacherToken, null), 200);
        body(send("POST", path(gamma) + "/archive", teacherToken, null), 200);
        JsonNode first = body(send("GET", "/api/assignments?sort=title,asc&size=2", teacherToken, null), 200);
        assertThat(first.get("totalElements").asLong()).isEqualTo(3);
        assertThat(first.get("totalPages").asInt()).isEqualTo(2);
        assertThat(first.at("/content/0/id").asText()).isEqualTo(alpha.toString());
        assertThat(first.at("/content/1/id").asText()).isEqualTo(beta.toString());
        JsonNode second = body(send("GET", "/api/assignments?sort=title,asc&size=2&page=1", teacherToken, null), 200);
        assertThat(second.get("content").size()).isEqualTo(1);
        assertThat(second.at("/content/0/id").asText()).isEqualTo(gamma.toString());
        assertThat(body(send("GET", "/api/assignments?page=50", teacherToken, null), 200).get("content").size()).isZero();
        assertThat(body(send("GET", "/api/assignments?status=PUBLISHED", teacherToken, null), 200).at("/content/0/id").asText()).isEqualTo(beta.toString());
        JsonNode tie0 = body(send("GET", "/api/assignments?sort=maximumScore,asc&size=1", teacherToken, null), 200);
        JsonNode tie1 = body(send("GET", "/api/assignments?sort=maximumScore,asc&size=1&page=1", teacherToken, null), 200);
        assertThat(tie0.at("/content/0/id")).isNotEqualTo(tie1.at("/content/0/id"));
        assertThat(body(send("GET", "/api/assignments?sort=maximumScore,asc&size=1", teacherToken, null), 200)).isEqualTo(tie0);
        for (String field : List.of("id", "title", "maximumScore", "deadline", "status", "createdAt", "updatedAt")) {
            body(send("GET", "/api/assignments?sort=" + field + ",desc", teacherToken, null), 200);
        }
        for (String query : List.of("page=-1", "size=0", "size=101", "sort=teacher.passwordHash,asc", "sort=title,up", "status=UNKNOWN", "page=no")) {
            body(send("GET", "/api/assignments?" + query, teacherToken, null), 400);
        }
        problem(send("GET", "/api/assignments/not-a-uuid", teacherToken, null), 400, "INVALID_REQUEST");
        problem(send("GET", path(UUID.randomUUID()), teacherToken, null), 404, "ASSIGNMENT_NOT_FOUND");
    }

    @Test
    void expiredDraftCannotPublishButCanBeArchivedAndRetained() throws Exception {
        // An assignment can naturally expire after creation. Simulate elapsed time without sleeping.
        Assignment expired = assignments.saveAndFlush(new Assignment(teacher, "Expired", "Old instructions", BigDecimal.TEN,
                Instant.now().minus(1, ChronoUnit.DAYS)));
        UUID id = expired.getId();
        problem(send("POST", path(id) + "/publish", teacherToken, null), 400, "INVALID_ASSIGNMENT");
        assertThat(body(send("POST", path(id) + "/archive", teacherToken, null), 200).get("status").asText()).isEqualTo("ARCHIVED");
        body(send("POST", path(id) + "/archive", teacherToken, null), 200);
        body(send("GET", path(id), teacherToken, null), 200);
        body(send("GET", path(id), adminToken, null), 200);
        problem(send("GET", path(id), studentToken, null), 404, "ASSIGNMENT_NOT_FOUND");
        problem(send("POST", path(id) + "/publish", teacherToken, null), 409, "ARCHIVED_ASSIGNMENT");
        problem(send("PUT", path(id) + "?createNewRevision=true", adminToken, metadata("Reopen")), 409, "ARCHIVED_ASSIGNMENT");
        problem(send("DELETE", path(id), adminToken, null), 409, "ARCHIVED_ASSIGNMENT");
        assertThat(assignments.existsById(id)).isTrue();
    }

    @Test
    void publishedEditsRequireExplicitRevisionAndReturnToDraft() throws Exception {
        UUID id = create("Published");
        addCase(id, false);
        body(send("POST", path(id) + "/publish", teacherToken, null), 200);
        problem(send("PUT", path(id), teacherToken, metadata("Changed")), 409, "REVISION_REQUIRED");
        assertThat(assignments.findById(id).orElseThrow().getTitle()).isEqualTo("Published");
        var response = send("PUT", path(id) + "?createNewRevision=true", teacherToken, metadata("Changed"));
        assertThat(body(response, 200).get("status").asText()).isEqualTo("DRAFT");
        assertRevision(response, 2);
        problem(send("GET", path(id), studentToken, null), 404, "ASSIGNMENT_NOT_FOUND");
        body(send("POST", path(id) + "/publish", teacherToken, null), 200);
        var explicit = send("PUT", path(id) + "?createNewRevision=true", teacherToken, metadata("Changed"));
        body(explicit, 200);
        assertRevision(explicit, 3);
    }

    @Test
    void revisionsAndArchivingPreserveOriginalTestsSubmissionsAndResults() throws Exception {
        UUID id = create("Original grading");
        AssignmentRevision original = latest(id);
        UUID submissionId = tx.execute(status -> {
            AssignmentRevision revision = revisions.findById(original.getId()).orElseThrow();
            TestCase test = cases.saveAndFlush(new TestCase(revision, "secret input", "expected", new BigDecimal("100"), true, 0));
            revision.seal();
            revisions.flush();
            Submission submission = context.getBean(SubmissionRepository.class).saveAndFlush(
                    new Submission(revision, student, "fixture/source.java", "Main.java", "JAVA"));
            context.getBean(SubmissionResultRepository.class).saveAndFlush(new SubmissionResult(submission, new BigDecimal("100"), 1, 1, 12L, Verdict.ACCEPTED));
            context.getBean(TestCaseResultRepository.class).saveAndFlush(new TestCaseResult(submission, test, Verdict.ACCEPTED, "expected", 12L, new BigDecimal("100")));
            return submission.getId();
        });
        var originalRows = jdbc.queryForList("SELECT * FROM assignment_revisions WHERE id = ?", original.getId());
        var originalCases = jdbc.queryForList("SELECT * FROM test_cases WHERE assignment_revision_id = ?", original.getId());
        var originalSubmission = jdbc.queryForList("SELECT * FROM submissions WHERE id = ?", submissionId);
        var originalResults = jdbc.queryForList("SELECT * FROM submission_results WHERE submission_id = ?", submissionId);
        var originalTestResults = jdbc.queryForList("SELECT * FROM test_case_results WHERE submission_id = ?", submissionId);
        body(send("POST", path(id) + "/publish", teacherToken, null), 200);
        problem(send("DELETE", path(id), teacherToken, null), 409, "ASSIGNMENT_HAS_SUBMISSIONS");
        var changed = metadata("New grading");
        changed.put("maximumScore", "200");
        var response = send("PUT", path(id) + "?createNewRevision=true", teacherToken, changed);
        body(response, 200);
        assertRevision(response, 2);
        AssignmentRevision next = latest(id);
        assertThat(next.getMaximumScore()).isEqualByComparingTo("200");
        assertThat(next.getSealedAt()).isNull();
        List<TestCase> copied = cases.findByRevisionIdOrderByExecutionOrder(next.getId());
        assertThat(copied).hasSize(1);
        assertThat(copied.getFirst().getId()).isNotEqualTo(originalCases.getFirst().get("id"));
        assertThat(copied.getFirst().getInputData()).isEqualTo("secret input");
        assertThat(copied.getFirst().getExpectedOutput()).isEqualTo("expected");
        assertThat(copied.getFirst().getPoints()).isEqualByComparingTo("100");
        assertThat(copied.getFirst().isHidden()).isTrue();
        assertThat(copied.getFirst().getExecutionOrder()).isZero();
        // A changed maximum never silently rescales copied tests or seals an invalid configuration.
        assertThatThrownBy(() -> jdbc.update("UPDATE assignment_revisions SET sealed_at = now() WHERE id = ?", next.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        body(send("POST", path(id) + "/archive", teacherToken, null), 200);
        assertThat(jdbc.queryForList("SELECT * FROM assignment_revisions WHERE id = ?", original.getId())).isEqualTo(originalRows);
        assertThat(jdbc.queryForList("SELECT * FROM test_cases WHERE assignment_revision_id = ?", original.getId())).isEqualTo(originalCases);
        assertThat(jdbc.queryForList("SELECT * FROM submissions WHERE id = ?", submissionId)).isEqualTo(originalSubmission);
        assertThat(jdbc.queryForList("SELECT * FROM submission_results WHERE submission_id = ?", submissionId)).isEqualTo(originalResults);
        assertThat(jdbc.queryForList("SELECT * FROM test_case_results WHERE submission_id = ?", submissionId)).isEqualTo(originalTestResults);
    }

    @Test
    void deleteRemovesUnsealedDraftDependenciesButRetainsSealedHistory() throws Exception {
        UUID removable = create("Disposable");
        addCase(removable, false);
        body(send("PUT", path(removable), teacherToken, metadata("Second draft")), 200);
        assertThat(send("DELETE", path(removable), teacherToken, null).statusCode()).isEqualTo(204);
        assertThat(assignments.existsById(removable)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM assignment_revisions WHERE assignment_id = ?", Long.class, removable)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM test_cases WHERE assignment_id = ?", Long.class, removable)).isZero();
        UUID retained = create("Sealed");
        addCase(retained, true);
        problem(send("DELETE", path(retained), teacherToken, null), 409, "GRADING_HISTORY_RETAINED");
        problem(send("PUT", path(retained), teacherToken, metadata("Unsafe")), 409, "REVISION_REQUIRED");
        assertThat(assignments.existsById(retained)).isTrue();
    }

    @Test
    void databaseRejectsInPlaceRevisionMetadataChangesEvenBeforeSealing() throws Exception {
        UUID id = create("Immutable metadata");
        UUID revision = latest(id).getId();
        for (String change : List.of("title = 'Changed'", "description = 'Changed'", "maximum_score = 200",
                "deadline = deadline + interval '1 day'", "revision_number = 2", "created_at = created_at + interval '1 second'")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE assignment_revisions SET " + change + " WHERE id = ?", revision))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(latest(id).getTitle()).isEqualTo("Immutable metadata");
    }

    @Test
    void concurrentExplicitEditsAllocateDistinctRevisionsWithoutLosingHistory() throws Exception {
        UUID id = create("Concurrent");
        addCase(id, false);
        body(send("POST", path(id) + "/publish", teacherToken, null), 200);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return send("PUT", path(id) + "?createNewRevision=true", teacherToken, metadata("Edit A"));
            });
            var b = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return send("PUT", path(id) + "?createNewRevision=true", teacherToken, metadata("Edit B"));
            });
            var first = a.get(20, TimeUnit.SECONDS);
            var second = b.get(20, TimeUnit.SECONDS);
            body(first, 200);
            body(second, 200);
            assertThat(List.of(first.headers().firstValue("X-Assignment-Revision").orElseThrow(),
                    second.headers().firstValue("X-Assignment-Revision").orElseThrow())).containsExactlyInAnyOrder("2", "3");
        }
        assertThat(jdbc.queryForList("SELECT title FROM assignment_revisions WHERE assignment_id = ?", String.class, id))
                .containsExactlyInAnyOrder("Concurrent", "Edit A", "Edit B");
        assertThat(assignments.findById(id).orElseThrow().getTitle()).isEqualTo(latest(id).getTitle());
    }

    @Test
    void serviceBoundaryRechecksRevokedOrDemotedIdentity() {
        AuthenticatedUser old = new AuthenticatedUser(teacher.getId(), Role.TEACHER, teacher.getTokenVersion());
        AssignmentRequest request = new AssignmentRequest("Forbidden", "Instructions", BigDecimal.TEN, Instant.parse(DEADLINE));
        jdbc.update("UPDATE users SET role = 'STUDENT' WHERE id = ?", teacher.getId());
        withIdentity(old, () -> assertThatThrownBy(() -> context.getBean(AssignmentService.class).create(request))
                .isInstanceOf(AccessDeniedException.class));
        jdbc.update("UPDATE users SET role = 'TEACHER', enabled = false WHERE id = ?", teacher.getId());
        withIdentity(old, () -> assertThatThrownBy(() -> context.getBean(AssignmentService.class).create(request))
                .isInstanceOf(BadCredentialsException.class));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM assignments WHERE teacher_id = ?", Long.class, teacher.getId())).isZero();
    }

    @Test
    void openApiDocumentsAllRoutesRevisionOptInAndSafeResponseSchema() throws Exception {
        JsonNode docs = body(send("GET", "/v3/api-docs", null, null), 200);
        for (Route route : List.of(new Route("post", "/api/assignments", null), new Route("get", "/api/assignments", null),
                new Route("get", "/api/assignments/{assignmentId}", null), new Route("put", "/api/assignments/{assignmentId}", null),
                new Route("delete", "/api/assignments/{assignmentId}", null), new Route("post", "/api/assignments/{assignmentId}/publish", null),
                new Route("post", "/api/assignments/{assignmentId}/archive", null))) {
            JsonNode operation = docs.get("paths").get(route.path()).get(route.method());
            assertThat(operation).isNotNull();
            assertThat(operation.get("security").toString()).contains("bearerAuth");
        }
        assertThat(docs.get("paths").get("/api/assignments/{assignmentId}").get("put").get("parameters").toString()).contains("createNewRevision");
        assertThat(docs.at("/components/schemas/AssignmentResponse/properties").size()).isEqualTo(9);
    }

    private UUID create(String title) throws Exception {
        return UUID.fromString(body(send("POST", "/api/assignments", teacherToken, metadata(title)), 201).get("id").asText());
    }

    private static void addCase(UUID id, boolean seal) {
        tx.executeWithoutResult(status -> {
            AssignmentRevision revision = latest(id);
            cases.saveAndFlush(new TestCase(revision, "in", "out", new BigDecimal("100"), true, 0));
            if (seal) { revision.seal(); revisions.flush(); }
        });
    }

    private static AssignmentRevision latest(UUID id) { return revisions.findFirstByAssignmentIdOrderByRevisionNumberDesc(id).orElseThrow(); }
    private static String path(UUID id) { return "/api/assignments/" + id; }
    private static Map<String, Object> metadata(String title) {
        return new HashMap<>(Map.of("title", title, "description", "Solve the problem", "maximumScore", "100", "deadline", DEADLINE));
    }
    private static User account(Role role) {
        String name = "assignment-" + UUID.randomUUID().toString().substring(0, 12);
        return users.saveAndFlush(new User(name, name + "@example.test", passwordHash, role));
    }
    private static String login(User user) throws Exception {
        return body(send("POST", "/api/auth/login", null, Map.of("identifier", user.getUsername(), "password", PASSWORD)), 200).get("accessToken").asText();
    }
    private static HttpResponse<String> send(String method, String path, String token, Object payload) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (payload != null) request.header("Content-Type", "application/json");
        request.method(method, payload == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload)));
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private static JsonNode body(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return JSON.readTree(response.body());
    }
    private static void problem(HttpResponse<String> response, int status, String code) {
        assertThat(body(response, status).get("code").asText()).isEqualTo(code);
        assertThat(response.headers().firstValue("content-type").orElse("")).startsWith("application/problem+json");
    }
    private static void assertMetadata(JsonNode node) {
        assertThat(node.size()).isEqualTo(9);
        for (String field : List.of("id", "title", "description", "maximumScore", "deadline", "status", "teacherId", "createdAt", "updatedAt")) {
            assertThat(node.has(field)).as(field).isTrue();
        }
        assertThat(node.toString()).doesNotContain(passwordHash, PASSWORD, "inputData", "expectedOutput");
    }
    private static void assertRevision(HttpResponse<String> response, int revision) {
        assertThat(response.headers().firstValue("X-Assignment-Revision")).contains(Integer.toString(revision));
    }
    private static void withIdentity(AuthenticatedUser identity, Runnable action) {
        var previous = SecurityContextHolder.getContext();
        var security = SecurityContextHolder.createEmptyContext();
        security.setAuthentication(new PreAuthenticatedAuthenticationToken(identity, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + identity.role().name()))));
        SecurityContextHolder.setContext(security);
        try { action.run(); } finally { SecurityContextHolder.setContext(previous); }
    }
    private record Route(String method, String path, Object payload) { }
}
