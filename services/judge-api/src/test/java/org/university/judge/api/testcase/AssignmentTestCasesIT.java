package org.university.judge.api.testcase;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.university.judge.api.JudgeApiApplication;
import org.university.judge.api.assignment.persistence.AssignmentRevisionRepository;
import org.university.judge.api.result.SubmissionResult;
import org.university.judge.api.result.SubmissionResultRepository;
import org.university.judge.api.result.TestCaseResult;
import org.university.judge.api.result.TestCaseResultRepository;
import org.university.judge.api.result.Verdict;
import org.university.judge.api.submission.domain.Submission;
import org.university.judge.api.submission.persistence.SubmissionRepository;
import org.university.judge.api.support.TestJwtKeys;
import org.university.judge.api.testcase.persistence.TestCaseRepository;
import org.university.judge.api.user.Role;
import org.university.judge.api.user.User;
import org.university.judge.api.user.UserRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class AssignmentTestCasesIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withPassword(UUID.randomUUID().toString());
    private static final String PASSWORD = "TestCases9!" + UUID.randomUUID();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static ConfigurableApplicationContext context;
    private static UserRepository users;
    private static AssignmentRevisionRepository revisions;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static String base;
    private static String passwordHash;
    private static String adminToken;
    private static String studentToken;
    private static String otherTeacherToken;
    private static User student;
    private String teacherToken;
    private UUID assignment;

    @BeforeAll
    static void startApplication() throws Exception {
        context = new SpringApplicationBuilder(JudgeApiApplication.class).run(TestJwtKeys.arguments(
                "--server.port=0", "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(), "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.data.redis.password=unused", "--management.health.redis.enabled=false",
                "--management.endpoint.health.group.readiness.include=readinessState,db"));
        users = context.getBean(UserRepository.class);
        revisions = context.getBean(AssignmentRevisionRepository.class);
        jdbc = context.getBean(JdbcTemplate.class);
        tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        base = "http://localhost:" + context.getEnvironment().getRequiredProperty("local.server.port");
        passwordHash = context.getBean(PasswordEncoder.class).encode(PASSWORD);
        adminToken = login(account(Role.ADMIN));
        student = account(Role.STUDENT);
        studentToken = login(student);
        otherTeacherToken = login(account(Role.TEACHER));
    }

    @BeforeEach
    void isolatedAssignment() throws Exception {
        teacherToken = login(account(Role.TEACHER));
        assignment = createAssignment(teacherToken);
    }

    @AfterAll
    static void closeApplication() { if (context != null) context.close(); }

    @Test
    void createUpdateAndDeleteDraftCasesWithEmptyDataAndExactDtoFields() throws Exception {
        var request = test("", "", "40.1250", false, 7);
        var created = send("POST", path(), teacherToken, request);
        JsonNode first = body(created, 201);
        assertFields(first);
        assertThat(first.get("assignmentId").asText()).isEqualTo(assignment.toString());
        assertThat(first.get("inputData").asText()).isEmpty();
        assertThat(first.get("expectedOutput").asText()).isEmpty();
        assertThat(first.get("points").decimalValue()).isEqualByComparingTo("40.1250");
        assertThat(created.headers().firstValue("Location")).contains(path() + "/" + id(first));
        assertRevision(created, 1);
        var updated = send("PUT", path() + "/" + id(first), teacherToken, test("new\n", "answer\n", "100", true, 0));
        JsonNode changed = body(updated, 200);
        assertFields(changed);
        assertThat(id(changed)).isEqualTo(id(first));
        assertThat(changed.get("isHidden").asBoolean()).isTrue();
        assertThat(changed.get("inputData").asText()).isEqualTo("new\n");
        JsonNode stored = body(send("GET", path() + "/" + id(first), teacherToken, null), 200);
        assertThat(stored.get("points").decimalValue()).isEqualByComparingTo(changed.get("points").decimalValue());
        for (String field : List.of("id", "assignmentId", "inputData", "expectedOutput", "isHidden", "executionOrder")) {
            assertThat(stored.get(field)).isEqualTo(changed.get(field));
        }
        var deleted = send("DELETE", path() + "/" + id(first), teacherToken, null);
        assertThat(deleted.statusCode()).isEqualTo(204);
        assertThat(deleted.body()).isEmpty();
        assertRevision(deleted, 1);
        assertThat(body(send("GET", path(), teacherToken, null), 200).size()).isZero();
        problem(send("GET", path() + "/" + id(first), teacherToken, null), 404, "TEST_CASE_NOT_FOUND");
    }

    @Test
    void requiredFieldsPositivePointsAndNonnegativeOrderAreValidated() throws Exception {
        UUID id = createCase("100", false, 0);
        for (String required : List.of("inputData", "expectedOutput", "points", "executionOrder")) {
            var missing = test("", "", "100", false, 0);
            missing.remove(required);
            body(send("POST", path(), teacherToken, missing), 400);
            body(send("PUT", path() + "/" + id, teacherToken, missing), 400);
            missing.put(required, null);
            body(send("POST", path(), teacherToken, missing), 400);
        }
        for (var invalid : List.of(Map.entry("points", "0"), Map.entry("points", "-1"), Map.entry("points", "0.00001"),
                Map.entry("points", "1000000000000000"), Map.entry("executionOrder", "-1"), Map.entry("executionOrder", "2147483648"),
                Map.entry("isHidden", "invalid"), Map.entry("assignmentId", assignment.toString()))) {
            var request = test("", "", "100", false, 0);
            request.put(invalid.getKey(), invalid.getValue());
            body(send("POST", path(), teacherToken, request), 400);
            body(send("PUT", path() + "/" + id, teacherToken, request), 400);
        }
        var defaults = test("", "", "1", false, 1);
        defaults.remove("isHidden");
        assertThat(body(send("POST", path(), teacherToken, defaults), 201).get("isHidden").asBoolean()).isTrue();
        body(send("GET", path() + "/bad-uuid", teacherToken, null), 400);
    }

    @Test
    void rejectsDuplicateOrdersOnCreateAndUpdateAndSortsByOrder() throws Exception {
        UUID late = createCase("50", false, 10);
        UUID early = createCase("50", false, 0);
        problem(send("POST", path(), teacherToken, test("duplicate", "", "1", true, 0)), 409, "DUPLICATE_EXECUTION_ORDER");
        problem(send("PUT", path() + "/" + late, teacherToken, test("duplicate", "", "50", false, 0)), 409, "DUPLICATE_EXECUTION_ORDER");
        JsonNode listing = body(send("GET", path(), teacherToken, null), 200);
        assertThat(id(listing.get(0))).isEqualTo(early);
        assertThat(id(listing.get(1))).isEqualTo(late);
        body(send("PUT", path() + "/" + early, teacherToken, test("same order", "", "50", false, 0)), 200);
        UUID another = createAssignment(teacherToken);
        body(send("POST", path(another), teacherToken, test("", "", "100", true, 0)), 201);
    }

    @Test
    void allRoutesRequireAuthenticationAndMutationsRequireOwnerOrAdmin() throws Exception {
        UUID id = createCase("100", false, 0);
        List<Route> writes = List.of(new Route("POST", path(), test("", "", "1", false, 1)),
                new Route("PUT", path() + "/" + id, test("", "", "100", false, 0)),
                new Route("DELETE", path() + "/" + id, null));
        for (Route route : writes) {
            problem(send(route.method(), route.path(), null, route.payload()), 401, "UNAUTHORIZED");
            problem(send(route.method(), route.path(), studentToken, route.payload()), 403, "FORBIDDEN");
            problem(send(route.method(), route.path(), otherTeacherToken, route.payload()), 403, "FORBIDDEN");
        }
        for (String route : List.of(path(), path() + "/" + id)) {
            problem(send("GET", route, null, null), 401, "UNAUTHORIZED");
            problem(send("GET", route, otherTeacherToken, null), 404, "ASSIGNMENT_NOT_FOUND");
        }
        UUID foreignAssignment = createAssignment(otherTeacherToken);
        for (Route route : List.of(new Route("GET", path(foreignAssignment) + "/" + id, null),
                new Route("PUT", path(foreignAssignment) + "/" + id, test("", "", "100", true, 0)),
                new Route("DELETE", path(foreignAssignment) + "/" + id, null))) {
            problem(send(route.method(), route.path(), otherTeacherToken, route.payload()), 404, "TEST_CASE_NOT_FOUND");
            problem(send(route.method(), route.path(), adminToken, route.payload()), 404, "TEST_CASE_NOT_FOUND");
        }
    }

    @Test
    void administratorsCanManageTestsForAnyAssignment() throws Exception {
        UUID id = id(body(send("POST", path(), adminToken, test("", "", "100", true, 0)), 201));
        assertThat(body(send("GET", path(), adminToken, null), 200).size()).isEqualTo(1);
        body(send("GET", path() + "/" + id, adminToken, null), 200);
        body(send("PUT", path() + "/" + id, adminToken, test("updated", "", "100", false, 0)), 200);
        assertThat(send("DELETE", path() + "/" + id, adminToken, null).statusCode()).isEqualTo(204);
    }

    @Test
    void studentsSeeOnlyPublicCasesOfPublishedSealedRevision() throws Exception {
        UUID visible = createCase("25", false, 0);
        String secret = "hidden-" + UUID.randomUUID();
        UUID hidden = id(body(send("POST", path(), teacherToken, test(secret, secret, "75", true, 1)), 201));
        problem(send("GET", path(), studentToken, null), 404, "ASSIGNMENT_NOT_FOUND");
        problem(send("GET", path() + "/" + visible, studentToken, null), 404, "ASSIGNMENT_NOT_FOUND");
        publish(200);
        var listing = send("GET", path(), studentToken, null);
        JsonNode tests = body(listing, 200);
        assertThat(tests.size()).isEqualTo(1);
        assertThat(id(tests.get(0))).isEqualTo(visible);
        assertFields(tests.get(0));
        assertThat(listing.body()).doesNotContain(secret, hidden.toString());
        assertThat(tests.get(0).get("isHidden").asBoolean()).isFalse();
        body(send("GET", path() + "/" + visible, studentToken, null), 200);
        var denied = send("GET", path() + "/" + hidden, studentToken, null);
        problem(denied, 404, "TEST_CASE_NOT_FOUND");
        assertThat(denied.body()).doesNotContain(secret);
        assertThat(body(send("GET", path(), teacherToken, null), 200).size()).isEqualTo(2);
        assertThat(body(send("GET", path(), adminToken, null), 200).size()).isEqualTo(2);
        body(send("POST", assignmentPath() + "/archive", teacherToken, null), 200);
        problem(send("GET", path(), studentToken, null), 404, "ASSIGNMENT_NOT_FOUND");
        body(send("GET", path() + "/" + hidden, adminToken, null), 200);
        for (Route route : List.of(new Route("POST", path(), test("", "", "1", true, 2)),
                new Route("PUT", path() + "/" + hidden, test("", "", "75", true, 1)), new Route("DELETE", path() + "/" + hidden, null))) {
            problem(send(route.method(), route.path(), teacherToken, route.payload()), 409, "ARCHIVED_ASSIGNMENT");
        }
    }

    @Test
    void publicationRequiresNonemptyValidTestsAndExactDecimalScoreThenSeals() throws Exception {
        problem(publish(409), 409, "TEST_CASES_REQUIRED");
        UUID first = createCase("33.3333", false, 0);
        problem(publish(409), 409, "TEST_CASE_SCORE_MISMATCH");
        UUID second = createCase("66.6666", true, 1);
        problem(publish(409), 409, "TEST_CASE_SCORE_MISMATCH");
        body(send("PUT", path() + "/" + second, teacherToken, test("", "", "66.6668", true, 1)), 200);
        problem(publish(409), 409, "TEST_CASE_SCORE_MISMATCH");
        body(send("PUT", path() + "/" + second, teacherToken, test("", "", "66.6667", true, 1)), 200);
        assertThat(latest().getSealedAt()).isNull();
        publish(200);
        var sealed = latest();
        assertThat(sealed.getSealedAt()).isNotNull();
        publish(200);
        assertThat(latest().getId()).isEqualTo(sealed.getId());
        assertThatThrownBy(() -> jdbc.update("UPDATE test_cases SET input_data = 'changed' WHERE id = ?", first))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM test_cases WHERE id = ?", second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void publishedUpdateForksAndRetainsExactGradingUsedBySubmissionsAndResults() throws Exception {
        UUID originalCase = createCase("100", true, 0);
        publish(200);
        UUID originalRevision = latest().getId();
        UUID submissionId = tx.execute(status -> {
            var revision = revisions.findById(originalRevision).orElseThrow();
            var test = context.getBean(TestCaseRepository.class).findById(originalCase).orElseThrow();
            var submission = context.getBean(SubmissionRepository.class).saveAndFlush(
                    new Submission(revision, student, "fixture/source.java", "Main.java", "JAVA"));
            context.getBean(SubmissionResultRepository.class).saveAndFlush(
                    new SubmissionResult(submission, new BigDecimal("100"), 1, 1, 10L, Verdict.ACCEPTED));
            context.getBean(TestCaseResultRepository.class).saveAndFlush(
                    new TestCaseResult(submission, test, Verdict.ACCEPTED, "output", 10L, new BigDecimal("100")));
            return submission.getId();
        });
        var revisionBefore = jdbc.queryForList("SELECT * FROM assignment_revisions WHERE id = ?", originalRevision);
        var testsBefore = jdbc.queryForList("SELECT * FROM test_cases WHERE id = ?", originalCase);
        var submissionsBefore = jdbc.queryForList("SELECT * FROM submissions WHERE id = ?", submissionId);
        var resultsBefore = jdbc.queryForList("SELECT * FROM submission_results WHERE submission_id = ?", submissionId);
        var caseResultsBefore = jdbc.queryForList("SELECT * FROM test_case_results WHERE submission_id = ?", submissionId);
        var changed = send("PUT", path() + "/" + originalCase, teacherToken, test("new input", "new output", "100", false, 3));
        UUID replacement = id(body(changed, 200));
        assertThat(replacement).isNotEqualTo(originalCase);
        assertRevision(changed, 2);
        assertThat(latest().getSealedAt()).isNull();
        assertThat(body(send("GET", assignmentPath(), teacherToken, null), 200).get("status").asText()).isEqualTo("DRAFT");
        problem(send("GET", path(), studentToken, null), 404, "ASSIGNMENT_NOT_FOUND");
        for (Route route : List.of(new Route("GET", path() + "/" + originalCase, null),
                new Route("PUT", path() + "/" + originalCase, test("", "", "100", false, 0)),
                new Route("DELETE", path() + "/" + originalCase, null))) {
            problem(send(route.method(), route.path(), teacherToken, route.payload()), 404, "TEST_CASE_NOT_FOUND");
        }
        publish(200);
        assertThat(id(body(send("GET", path(), studentToken, null), 200).get(0))).isEqualTo(replacement);
        problem(send("GET", path() + "/" + originalCase, studentToken, null), 404, "TEST_CASE_NOT_FOUND");
        var deleted = send("DELETE", path() + "/" + replacement, teacherToken, null);
        assertThat(deleted.statusCode()).isEqualTo(204);
        assertRevision(deleted, 3);
        problem(publish(409), 409, "TEST_CASES_REQUIRED");
        assertThat(jdbc.queryForList("SELECT * FROM assignment_revisions WHERE id = ?", originalRevision)).isEqualTo(revisionBefore);
        assertThat(jdbc.queryForList("SELECT * FROM test_cases WHERE id = ?", originalCase)).isEqualTo(testsBefore);
        assertThat(jdbc.queryForList("SELECT * FROM submissions WHERE id = ?", submissionId)).isEqualTo(submissionsBefore);
        assertThat(jdbc.queryForList("SELECT * FROM submission_results WHERE submission_id = ?", submissionId)).isEqualTo(resultsBefore);
        assertThat(jdbc.queryForList("SELECT * FROM test_case_results WHERE submission_id = ?", submissionId)).isEqualTo(caseResultsBefore);
    }

    @Test
    void publishedCreateCopiesExistingCasesAndInvalidWritesDoNotFork() throws Exception {
        UUID old = createCase("100", false, 0);
        publish(200);
        UUID sealed = latest().getId();
        problem(send("POST", path(), teacherToken, test("", "", "10", true, 0)), 409, "DUPLICATE_EXECUTION_ORDER");
        body(send("POST", path(), teacherToken, test("", "", "0", true, 1)), 400);
        assertThat(latest().getId()).isEqualTo(sealed);
        var created = send("POST", path(), teacherToken, test("added", "answer", "10", true, 1));
        body(created, 201);
        assertRevision(created, 2);
        JsonNode list = body(send("GET", path(), teacherToken, null), 200);
        assertThat(list.size()).isEqualTo(2);
        assertThat(id(list.get(0))).isNotEqualTo(old);
        assertThat(list.get(0).get("points").decimalValue()).isEqualByComparingTo("100");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM test_cases WHERE assignment_revision_id = ?", Long.class, sealed)).isEqualTo(1);
        problem(publish(409), 409, "TEST_CASE_SCORE_MISMATCH");
    }

    @Test
    void concurrentDuplicateCreatesAndPublishVersusEditPreserveInvariants() throws Exception {
        var request = test("", "", "100", false, 0);
        var responses = concurrently(() -> send("POST", path(), teacherToken, request), () -> send("POST", path(), teacherToken, request));
        assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(201, 409);
        JsonNode listing = body(send("GET", path(), teacherToken, null), 200);
        UUID id = id(listing.get(0));
        var races = concurrently(() -> send("POST", assignmentPath() + "/publish", teacherToken, null),
                () -> send("PUT", path() + "/" + id, teacherToken, test("changed", "", "99", false, 0)));
        assertThat(races.get(1).statusCode()).as(races.get(1).body()).isEqualTo(200);
        assertThat(races.get(0).statusCode()).as(races.get(0).body()).isIn(200, 409);
        assertThat(body(send("GET", assignmentPath(), teacherToken, null), 200).get("status").asText()).isEqualTo("DRAFT");
        assertThat(latest().getSealedAt()).isNull();
        assertThat(body(send("GET", path(), teacherToken, null), 200).get(0).get("points").decimalValue()).isEqualByComparingTo("99");
    }

    @Test
    void stageSixMigrationRetainsLegacyHistoryAndRequiresUnreadyAssignmentsToRepublish() throws Exception {
        String schema = "test_case_upgrade";
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).target("6").load().migrate();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            connection.setSchema(schema);
            JdbcTemplate db = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            UUID teacher = UUID.randomUUID();
            db.update("INSERT INTO users(id, username, email, password_hash, role) VALUES (?, 'legacy', 'legacy@example.test', 'fixture', 'TEACHER')", teacher);
            UUID metadataOnly = legacyAssignment(db, teacher);
            UUID valid = legacyAssignment(db, teacher);
            UUID legacyZero = legacyAssignment(db, teacher);
            UUID validRevision = legacyRevision(db, valid);
            UUID zeroRevision = legacyRevision(db, legacyZero);
            db.update("INSERT INTO test_cases(id, assignment_id, assignment_revision_id, input_data, expected_output, points, execution_order) VALUES (?, ?, ?, '', '', 0, 1)",
                    UUID.randomUUID(), legacyZero, zeroRevision);
            db.update("UPDATE assignment_revisions SET sealed_at = now() WHERE id IN (?, ?)", validRevision, zeroRevision);
            var original = db.queryForList("SELECT * FROM test_cases WHERE assignment_revision_id = ? ORDER BY execution_order", zeroRevision);
            var originalRevision = db.queryForList("SELECT * FROM assignment_revisions WHERE id = ?", zeroRevision);
            Flyway upgraded = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .schemas(schema).defaultSchema(schema).load();
            assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(4);
            upgraded.validate();
            assertThat(db.queryForObject("SELECT status FROM assignments WHERE id = ?", String.class, metadataOnly)).isEqualTo("DRAFT");
            assertThat(db.queryForObject("SELECT status FROM assignments WHERE id = ?", String.class, legacyZero)).isEqualTo("DRAFT");
            assertThat(db.queryForObject("SELECT status FROM assignments WHERE id = ?", String.class, valid)).isEqualTo("PUBLISHED");
            assertThat(db.queryForList("SELECT * FROM test_cases WHERE assignment_revision_id = ? ORDER BY execution_order", zeroRevision)).isEqualTo(original);
            assertThat(db.queryForList("SELECT * FROM assignment_revisions WHERE id = ?", zeroRevision)).isEqualTo(originalRevision);
            UUID draft = legacyRevision(db, metadataOnly);
            db.update("INSERT INTO test_cases(id, assignment_id, assignment_revision_id, input_data, expected_output, points, execution_order) VALUES (?, ?, ?, '', '', 0, 1)",
                    UUID.randomUUID(), metadataOnly, draft);
            assertThatThrownBy(() -> db.update("UPDATE assignment_revisions SET sealed_at = now() WHERE id = ?", draft)).isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test
    void openApiDocumentsFiveRoutesAndStudentVisibility() throws Exception {
        JsonNode docs = body(send("GET", "/v3/api-docs", null, null), 200);
        String collection = "/api/assignments/{assignmentId}/test-cases";
        for (Route route : List.of(new Route("get", collection, null), new Route("post", collection, null),
                new Route("get", collection + "/{testCaseId}", null), new Route("put", collection + "/{testCaseId}", null),
                new Route("delete", collection + "/{testCaseId}", null))) {
            JsonNode operation = docs.get("paths").get(route.path()).get(route.method());
            assertThat(operation).isNotNull();
            assertThat(operation.get("security").toString()).contains("bearerAuth");
        }
        assertThat(docs.at("/components/schemas/TestCaseResponse/properties").size()).isEqualTo(7);
        assertThat(docs.get("paths").get(collection).get("get").get("description").asText()).contains("public");
    }

    private static UUID legacyAssignment(JdbcTemplate db, UUID teacher) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO assignments(id, teacher_id, title, description, maximum_score, deadline, status) VALUES (?, ?, 'Legacy', 'Instructions', 100, '2030-01-01T00:00:00Z', 'PUBLISHED')", id, teacher);
        return id;
    }
    private static UUID legacyRevision(JdbcTemplate db, UUID assignment) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO assignment_revisions(id, assignment_id, revision_number, title, description, maximum_score, deadline) SELECT ?, id, 1, title, description, maximum_score, deadline FROM assignments WHERE id = ?", id, assignment);
        db.update("INSERT INTO test_cases(id, assignment_id, assignment_revision_id, input_data, expected_output, points, execution_order) VALUES (?, ?, ?, '', '', 100, 0)", UUID.randomUUID(), assignment, id);
        return id;
    }
    private org.university.judge.api.assignment.domain.AssignmentRevision latest() {
        return revisions.findFirstByAssignmentIdOrderByRevisionNumberDesc(assignment).orElseThrow();
    }
    private HttpResponse<String> publish(int status) throws Exception {
        var response = send("POST", assignmentPath() + "/publish", teacherToken, null);
        body(response, status);
        return response;
    }
    private UUID createCase(String points, boolean hidden, int order) throws Exception {
        return id(body(send("POST", path(), teacherToken, test("input", "output", points, hidden, order)), 201));
    }
    private static UUID createAssignment(String token) throws Exception {
        return id(body(send("POST", "/api/assignments", token, Map.of("title", "Test case assignment", "description", "Instructions", "maximumScore", 100,
                "deadline", Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString())), 201));
    }
    private String assignmentPath() { return "/api/assignments/" + assignment; }
    private String path() { return path(assignment); }
    private static String path(UUID assignment) { return "/api/assignments/" + assignment + "/test-cases"; }
    private static UUID id(JsonNode body) { return UUID.fromString(body.get("id").asText()); }
    private static Map<String, Object> test(String input, String expected, String points, boolean hidden, int order) {
        return new HashMap<>(Map.of("inputData", input, "expectedOutput", expected, "points", points, "isHidden", hidden, "executionOrder", order));
    }
    private static User account(Role role) {
        String name = "cases-" + UUID.randomUUID().toString().substring(0, 12);
        return users.saveAndFlush(new User(name, name + "@example.test", passwordHash, role));
    }
    private static String login(User user) throws Exception {
        return body(send("POST", "/api/auth/login", null, Map.of("identifier", user.getUsername(), "password", PASSWORD)), 200).get("accessToken").asText();
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
        assertThat(response.headers().firstValue("content-type").orElse("")).startsWith("application/problem+json");
    }
    private static void assertFields(JsonNode node) {
        assertThat(node.size()).isEqualTo(7);
        for (String field : List.of("id", "assignmentId", "inputData", "expectedOutput", "points", "isHidden", "executionOrder")) assertThat(node.has(field)).isTrue();
    }
    private static void assertRevision(HttpResponse<String> response, int revision) {
        assertThat(response.headers().firstValue("X-Assignment-Revision")).contains(Integer.toString(revision));
    }
    private static List<HttpResponse<String>> concurrently(Callable<HttpResponse<String>> first, Callable<HttpResponse<String>> second) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return first.call(); });
            var b = executor.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return second.call(); });
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
    }
    private record Route(String method, String path, Object payload) { }
}
