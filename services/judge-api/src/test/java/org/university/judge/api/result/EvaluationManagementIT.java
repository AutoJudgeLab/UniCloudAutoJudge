package org.university.judge.api.result;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.university.judge.api.JudgeApiApplication;
import org.university.judge.api.assignment.domain.*;
import org.university.judge.api.assignment.persistence.*;
import org.university.judge.api.result.application.*;
import org.university.judge.api.result.application.EvaluationCompletion.TestOutcome;
import org.university.judge.api.submission.domain.*;
import org.university.judge.api.submission.persistence.SubmissionRepository;
import org.university.judge.api.support.TestJwtKeys;
import org.university.judge.api.testcase.domain.TestCase;
import org.university.judge.api.testcase.persistence.TestCaseRepository;
import org.university.judge.api.user.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class EvaluationManagementIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withPassword(UUID.randomUUID().toString());
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String PASSWORD = "Evaluation9!" + UUID.randomUUID();
    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static EvaluationCompletionService completion;
    private static SubmissionRepository submissions;
    private static String base, hash;
    private User student, teacher;
    private String studentToken, teacherToken, adminToken;
    private Fixture fixture;
    private record Fixture(UUID assignment, AssignmentRevision revision, TestCase publicTest, TestCase hiddenTest) { }

    @BeforeAll static void start() {
        context = new SpringApplicationBuilder(JudgeApiApplication.class).run(TestJwtKeys.arguments(
                "--server.port=0", "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(), "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.data.redis.password=unused", "--management.health.redis.enabled=false",
                "--management.endpoint.health.group.readiness.include=readinessState,db", "--submission.cleanup.enabled=false"));
        jdbc = context.getBean(JdbcTemplate.class);
        tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        completion = context.getBean(EvaluationCompletionService.class);
        submissions = context.getBean(SubmissionRepository.class);
        hash = context.getBean(PasswordEncoder.class).encode(PASSWORD);
        base = "http://localhost:" + context.getEnvironment().getRequiredProperty("local.server.port");
    }
    @BeforeEach void setup() throws Exception {
        student = account(Role.STUDENT); teacher = account(Role.TEACHER);
        studentToken = login(student); teacherToken = login(teacher); adminToken = login(account(Role.ADMIN));
        fixture = assignment();
    }
    @AfterAll static void stop() { if (context != null) context.close(); }
    @AfterEach void clearIdentity() { SecurityContextHolder.clearContext(); }

    @Test void pendingAndRunningHaveNoInventedGrades() throws Exception {
        UUID id = pending(student);
        JsonNode result = get(resultPath(id), studentToken, 200);
        assertThat(result.get("status").asText()).isEqualTo("PENDING");
        for (String field : List.of("verdict", "totalScore", "maximumScore", "passedTests", "totalTests", "executionTimeMs", "completedAt", "testResults"))
            assertThat(result.path(field).isNull() || result.path(field).isMissingNode()).as(field).isTrue();
        begin(id);
        assertThat(get(resultPath(id), studentToken, 200).get("status").asText()).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM submission_results WHERE submission_id = ?", Long.class, id)).isZero();
    }

    @Test void completedResultUsesOriginalRevisionAndRedactsAllHiddenData() throws Exception {
        UUID id = pending(student); UUID attempt = begin(id);
        finish(report(id, attempt, true));
        JsonNode mine = get(resultPath(id), studentToken, 200);
        assertThat(mine.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(mine.get("verdict").asText()).isEqualTo("ACCEPTED");
        assertThat(mine.get("totalScore").decimalValue()).isEqualByComparingTo("100");
        assertThat(mine.get("maximumScore").decimalValue()).isEqualByComparingTo("100");
        assertThat(mine.get("passedTests").asInt()).isEqualTo(2);
        assertThat(mine.get("testResults").size()).isEqualTo(2);
        assertThat(mine.get("testResults").get(0).get("inputData").asText()).isEqualTo("public input");
        assertThat(mine.get("testResults").get(0).get("actualOutput").asText()).isEqualTo("public output");
        assertThat(mine.toString()).doesNotContain("hidden input", "hidden expected", "hidden actual", "sourceFileKey");
        assertThat(mine.get("testResults").get(1).get("awardedPoints").decimalValue()).isEqualByComparingTo("60");
        for (String token : List.of(teacherToken, adminToken)) {
            String visible = get(resultPath(id), token, 200).toString();
            assertThat(visible).contains("hidden input", "hidden expected", "hidden actual");
        }
        // Editable metadata no longer determines the historical result maximum or visibility.
        jdbc.update("UPDATE assignments SET maximum_score = 200, status = 'DRAFT' WHERE id = ?", fixture.assignment());
        assertThat(get(resultPath(id), studentToken, 200).get("maximumScore").decimalValue()).isEqualByComparingTo("100");
    }

    @Test void resultOwnershipAndAuthenticationAreEnforced() throws Exception {
        UUID id = pending(student);
        get(resultPath(id), null, 401); get(resultPath(id), "invalid", 401);
        get(resultPath(id), login(account(Role.STUDENT)), 404);
        get(resultPath(id), login(account(Role.TEACHER)), 404);
        get(resultPath(id), teacherToken, 200); get(resultPath(id), adminToken, 200);
        get(resultPath(UUID.randomUUID()), adminToken, 404);
        assertThat(send("POST", resultPath(id), studentToken, Map.of("totalScore", 100)).statusCode()).isEqualTo(405);
    }

    @Test void historyFiltersDatesAssignmentsStatusAndPaginationWithinOwnership() throws Exception {
        UUID first = pending(student); UUID second = pending(student); pending(account(Role.STUDENT));
        UUID attempt = begin(first); finish(report(first, attempt, true));
        Instant firstTime = submissions.findById(first).orElseThrow().getSubmittedAt();
        String mine = "/api/users/me/submissions?assignmentId=" + fixture.assignment();
        assertThat(get(mine, studentToken, 200).get("totalElements").asLong()).isEqualTo(2);
        assertThat(get(mine + "&status=COMPLETED", studentToken, 200).get("content").get(0).get("submissionId").asText()).isEqualTo(first.toString());
        assertThat(get(mine + "&submittedFrom=" + firstTime + "&submittedBefore=" + firstTime.plusNanos(1000), studentToken, 200).get("totalElements").asLong()).isEqualTo(1);
        assertThat(get(mine + "&submittedBefore=" + firstTime, studentToken, 200).get("totalElements").asLong()).isZero();
        assertThat(get(mine + "&page=1&size=1&sort=submittedAt,asc", studentToken, 200).get("content").get(0).get("submissionId").asText()).isEqualTo(second.toString());
        get(mine + "&submittedFrom=" + firstTime + "&submittedBefore=" + firstTime, studentToken, 400);
        get(mine + "&submittedFrom=invalid", studentToken, 400);
        String assignmentHistory = "/api/assignments/" + fixture.assignment() + "/submissions";
        assertThat(get(assignmentHistory, teacherToken, 200).get("totalElements").asLong()).isEqualTo(3);
        assertThat(get(assignmentHistory, adminToken, 200).get("totalElements").asLong()).isEqualTo(3);
        assertThat(get(assignmentHistory, studentToken, 200).get("totalElements").asLong()).isEqualTo(2);
        get(assignmentHistory, login(account(Role.TEACHER)), 404);
        assertThat(get("/api/users/me/submissions?assignmentId=" + UUID.randomUUID(), studentToken, 200).get("totalElements").asLong()).isZero();
    }

    @Test void leaderboardUsesBestScoreThenCompletionThenUuidWithGlobalPagedRanks() throws Exception {
        User second = account(Role.STUDENT), third = account(Role.STUDENT), fourth = account(Role.STUDENT);
        Instant time = Instant.now().plusSeconds(10).truncatedTo(ChronoUnit.MICROS);
        legacyCompleted(pending(student), false, time.minusSeconds(1));
        UUID winner = pending(student); legacyCompleted(winner, true, time);
        UUID later = pending(second); legacyCompleted(later, true, time.plusSeconds(1));
        UUID tied1 = pending(third), tied2 = pending(fourth);
        legacyCompleted(tied1, true, time.plusSeconds(2)); legacyCompleted(tied2, true, time.plusSeconds(2));
        List<String> tie = new ArrayList<>(List.of(tied1.toString(), tied2.toString())); Collections.sort(tie);
        JsonNode board = get(boardPath(), studentToken, 200);
        assertThat(board.get("totalElements").asInt()).isEqualTo(4);
        assertThat(board.get("content").get(0).get("submissionId").asText()).isEqualTo(winner.toString());
        assertThat(board.get("content").get(1).get("submissionId").asText()).isEqualTo(later.toString());
        assertThat(board.get("content").get(2).get("submissionId").asText()).isEqualTo(tie.get(0));
        assertThat(board.get("content").get(3).get("submissionId").asText()).isEqualTo(tie.get(1));
        assertThat(board.get("content").get(0).size()).isEqualTo(4);
        assertThat(board.get("content").get(0).get("username").asText()).isEqualTo(student.getUsername());
        JsonNode page = get(boardPath() + "?page=1&size=2", studentToken, 200);
        assertThat(page.get("content").get(0).get("rank").asLong()).isEqualTo(3);
        assertThat(page.get("totalPages").asInt()).isEqualTo(2);
        assertThat(get(boardPath() + "?page=9&size=2", studentToken, 200).get("content").size()).isZero();
        get(boardPath() + "?size=101", studentToken, 400);
    }

    @Test void sameStudentsEqualBestScoresChooseEarlierCompletion() throws Exception {
        UUID first = pending(student), second = pending(student);
        Instant time = Instant.now().plusSeconds(10);
        legacyCompleted(first, true, time); legacyCompleted(second, true, time.plusSeconds(1));
        assertThat(get(boardPath(), studentToken, 200).get("content").get(0).get("submissionId").asText()).isEqualTo(first.toString());
    }

    @Test void emptyLeaderboardExcludesPendingRunningInternalErrorsAndInconsistentLegacyResults() throws Exception {
        pending(student); begin(pending(student));
        UUID failed = pending(student), attempt = begin(failed);
        finish(new EvaluationCompletion(failed, attempt, Verdict.INTERNAL_ERROR, BigDecimal.ZERO, 0, 2, null, List.of()));
        UUID legacy = pending(student);
        tx.executeWithoutResult(status -> {
            var submission = submissions.findById(legacy).orElseThrow();
            context.getBean(SubmissionResultRepository.class).saveAndFlush(new SubmissionResult(submission, new BigDecimal("100"), 2, 2, 5L, Verdict.ACCEPTED));
            submission.setStatus(SubmissionStatus.COMPLETED); submission.setCompletedAt(Instant.now());
        });
        assertThat(get(boardPath(), studentToken, 200).get("content").size()).isZero();
    }

    @Test void validZeroCompilationScoreIsAvailableAndRankedWithoutInventingTestOutcomes() throws Exception {
        UUID id = pending(student), attempt = begin(id);
        finish(new EvaluationCompletion(id, attempt, Verdict.COMPILATION_ERROR, BigDecimal.ZERO, 0, 2, null, List.of()));
        JsonNode result = get(resultPath(id), studentToken, 200);
        assertThat(result.get("testResults").size()).isZero();
        assertThat(result.get("totalScore").decimalValue()).isEqualByComparingTo("0");
        assertThat(get(boardPath(), studentToken, 200).get("content").size()).isEqualTo(1);
    }

    @Test void leaderboardVisibilityFollowsPublicationAndTeacherOwnership() throws Exception {
        get(boardPath(), null, 401); get(boardPath(), login(account(Role.TEACHER)), 200);
        jdbc.update("UPDATE assignments SET status = 'ARCHIVED' WHERE id = ?", fixture.assignment());
        get(boardPath(), studentToken, 404); get(boardPath(), login(account(Role.TEACHER)), 404);
        get(boardPath(), teacherToken, 200); get(boardPath(), adminToken, 200);
    }

    @Test void duplicateCompletionIsIdempotentAcrossTestOrderAndDecimalScale() {
        UUID id = pending(student), attempt = begin(id);
        var report = report(id, attempt, true); finish(report);
        var before = submissions.findById(id).orElseThrow();
        var reversed = new ArrayList<>(report.tests()); Collections.reverse(reversed);
        finish(new EvaluationCompletion(id, attempt, Verdict.ACCEPTED, new BigDecimal("100.0000"), 2, 2, 12L, reversed));
        var after = submissions.findById(id).orElseThrow();
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        assertThat(after.getCompletedAt()).isEqualTo(before.getCompletedAt());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM test_case_results WHERE submission_id = ?", Long.class, id)).isEqualTo(2);
        assertThatThrownBy(() -> finish(report(id, attempt, false))).isInstanceOf(EvaluationConflictException.class);
    }

    @Test void staleAttemptsAndVersionsCannotOverwriteNewAttemptsOrCompletedResults() {
        UUID id = pending(student), old = begin(id), current = UUID.randomUUID();
        var version = submissions.findById(id).orElseThrow().getVersion();
        evaluator(() -> completion.begin(id, current, version));
        assertThatThrownBy(() -> finish(report(id, old, true))).isInstanceOf(EvaluationConflictException.class);
        assertThatThrownBy(() -> evaluator(() -> completion.begin(id, UUID.randomUUID(), version))).isInstanceOf(EvaluationConflictException.class);
        finish(report(id, current, false));
        assertThatThrownBy(() -> evaluator(() -> completion.begin(id, UUID.randomUUID(), submissions.findById(id).orElseThrow().getVersion())))
                .isInstanceOf(EvaluationConflictException.class);
        assertThat(submissions.findById(id).orElseThrow().getAttemptCount()).isEqualTo(2);
    }

    @Test void concurrentEquivalentCompletionsPersistOnceAndConflictsHaveOneWinner() throws Exception {
        UUID id = pending(student), attempt = begin(id); var report = report(id, attempt, true);
        assertThat(concurrent(() -> finish(report), () -> finish(report))).containsExactly("ok", "ok");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM submission_results WHERE submission_id = ?", Long.class, id)).isEqualTo(1);
        UUID conflict = pending(student), other = begin(conflict);
        assertThat(concurrent(() -> finish(report(conflict, other, true)), () -> finish(report(conflict, other, false))))
                .containsExactlyInAnyOrder("ok", "conflict");
    }

    @Test void invalidReportsRollbackAndDatabaseProtectsCommittedResults() {
        UUID id = pending(student), attempt = begin(id); var good = report(id, attempt, true);
        var wrongScore = new EvaluationCompletion(id, attempt, Verdict.ACCEPTED, BigDecimal.ZERO, 2, 2, 12L, good.tests());
        assertThatThrownBy(() -> finish(wrongScore)).isInstanceOf(EvaluationConflictException.class);
        var duplicate = new EvaluationCompletion(id, attempt, Verdict.ACCEPTED, new BigDecimal("100"), 2, 2, 12L,
                List.of(good.tests().getFirst(), good.tests().getFirst()));
        assertThatThrownBy(() -> finish(duplicate)).isInstanceOf(EvaluationConflictException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM submission_results WHERE submission_id = ?", Long.class, id)).isZero();
        assertThat(submissions.findById(id).orElseThrow().getStatus()).isEqualTo(SubmissionStatus.RUNNING);
        finish(good);
        assertThatThrownBy(() -> jdbc.update("UPDATE submission_results SET total_score = 0 WHERE submission_id = ?", id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM test_case_results WHERE submission_id = ?", id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE submissions SET status = 'PENDING' WHERE id = ?", id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void databaseFailureRollsBackAllOutcomesAndCompletionCanRetry() {
        UUID id = pending(student), attempt = begin(id);
        jdbc.execute("CREATE FUNCTION reject_test_summary() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test failure' USING ERRCODE = '23514'; END; $$");
        jdbc.execute("CREATE TRIGGER reject_test_summary BEFORE INSERT ON submission_results FOR EACH ROW EXECUTE FUNCTION reject_test_summary()");
        try {
            assertThatThrownBy(() -> finish(report(id, attempt, true))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM test_case_results WHERE submission_id = ?", Long.class, id)).isZero();
            assertThat(submissions.findById(id).orElseThrow().getStatus()).isEqualTo(SubmissionStatus.RUNNING);
        } finally {
            jdbc.execute("DROP TRIGGER reject_test_summary ON submission_results");
            jdbc.execute("DROP FUNCTION reject_test_summary()");
        }
        finish(report(id, attempt, true));
        assertThat(submissions.findById(id).orElseThrow().getStatus()).isEqualTo(SubmissionStatus.COMPLETED);
    }

    @Test void rejectsForeignRevisionTestsAndOversizedOutputs() {
        UUID id = pending(student), attempt = begin(id);
        var other = assignment();
        var invalid = new EvaluationCompletion(id, attempt, Verdict.ACCEPTED, new BigDecimal("100"), 2, 2, 12L,
                List.of(new TestOutcome(other.publicTest().getId(), Verdict.ACCEPTED, new BigDecimal("40"), 5L, "output"),
                        report(id, attempt, true).tests().get(1)));
        assertThatThrownBy(() -> finish(invalid)).isInstanceOf(EvaluationConflictException.class);
        var large = new EvaluationCompletion(id, attempt, Verdict.ACCEPTED, new BigDecimal("100"), 2, 2, 12L,
                List.of(new TestOutcome(fixture.publicTest().getId(), Verdict.ACCEPTED, new BigDecimal("40"), 5L, "a".repeat(65537)),
                        report(id, attempt, true).tests().get(1)));
        assertThatThrownBy(() -> finish(large)).isInstanceOf(EvaluationConflictException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM test_case_results WHERE submission_id = ?", Long.class, id)).isZero();
    }

    @Test void optimisticVersionRejectsStaleEntityUpdates() {
        UUID id = pending(student);
        var first = submissions.findById(id).orElseThrow();
        var stale = submissions.findById(id).orElseThrow();
        first.setAttemptCount(1); submissions.saveAndFlush(first);
        stale.setAttemptCount(2);
        assertThatThrownBy(() -> submissions.saveAndFlush(stale)).isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
    }

    @Test void normalRolesCannotInvokeInternalCompletionBoundary() {
        UUID id = pending(student);
        for (Role role : Role.values()) {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("local", null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + role))));
            assertThatThrownBy(() -> completion.begin(id, UUID.randomUUID(), 0)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        }
        var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("test")
                .header("alg", "RS256").subject(student.getId().toString()).claim("token_version", student.getTokenVersion())
                .claim("scope", "EVALUATION_WRITE").claim("authorities", List.of("EVALUATION_WRITE")).build();
        var local = context.getBean(org.university.judge.api.security.LocalUserJwtConverter.class).convert(jwt);
        assertThat(local.getAuthorities()).extracting(org.springframework.security.core.GrantedAuthority::getAuthority).containsExactly("ROLE_STUDENT");
    }

    @Test void openApiDocumentsResultLeaderboardAndDateFiltersButNoGradeWrite() throws Exception {
        JsonNode docs = get("/v3/api-docs", null, 200);
        JsonNode results = docs.at("/paths/~1api~1submissions~1{submissionId}~1result");
        assertThat(results.has("get")).isTrue(); assertThat(results.has("post")).isFalse();
        assertThat(docs.at("/paths/~1api~1assignments~1{assignmentId}~1leaderboard/get").isMissingNode()).isFalse();
        assertThat(docs.at("/paths/~1api~1users~1me~1submissions/get/parameters").toString()).contains("submittedFrom", "submittedBefore");
    }

    private Fixture assignment() {
        return tx.execute(status -> {
            var a = context.getBean(AssignmentRepository.class).saveAndFlush(new Assignment(teacher, "Evaluation assignment", "Instructions", new BigDecimal("100"), Instant.now().plusSeconds(3600)));
            var revision = context.getBean(AssignmentRevisionRepository.class).saveAndFlush(new AssignmentRevision(a, 1));
            var cases = context.getBean(TestCaseRepository.class);
            var publicTest = cases.saveAndFlush(new TestCase(revision, "public input", "public expected", new BigDecimal("40"), false, 0));
            var hiddenTest = cases.saveAndFlush(new TestCase(revision, "hidden input", "hidden expected", new BigDecimal("60"), true, 1));
            revision.seal(); a.setStatus(AssignmentStatus.PUBLISHED);
            return new Fixture(a.getId(), revision, publicTest, hiddenTest);
        });
    }
    private UUID pending(User author) {
        return submissions.saveAndFlush(new Submission(fixture.revision(), author, "submissions/" + UUID.randomUUID() + "/source/Main.java", "Main.java", "JAVA")).getId();
    }
    private UUID begin(UUID id) {
        UUID attempt = UUID.randomUUID(); evaluator(() -> completion.begin(id, attempt, 0)); return attempt;
    }
    private EvaluationCompletion report(UUID id, UUID attempt, boolean allPassed) {
        return new EvaluationCompletion(id, attempt, allPassed ? Verdict.ACCEPTED : Verdict.WRONG_ANSWER,
                new BigDecimal(allPassed ? "100" : "40"), allPassed ? 2 : 1, 2, 12L,
                List.of(new TestOutcome(fixture.publicTest().getId(), Verdict.ACCEPTED, new BigDecimal("40"), 5L, "public output"),
                        new TestOutcome(fixture.hiddenTest().getId(), allPassed ? Verdict.ACCEPTED : Verdict.WRONG_ANSWER,
                                new BigDecimal(allPassed ? "60" : "0"), 7L, "hidden actual")));
    }
    private void finish(EvaluationCompletion report) { evaluator(() -> { completion.complete(report); return null; }); }
    private static <T> T evaluator(Supplier<T> work) {
        var previous = SecurityContextHolder.getContext();
        var trusted = SecurityContextHolder.createEmptyContext();
        trusted.setAuthentication(new UsernamePasswordAuthenticationToken("test-evaluator", null, List.of(new SimpleGrantedAuthority("EVALUATION_WRITE"))));
        SecurityContextHolder.setContext(trusted);
        try { return work.get(); } finally { SecurityContextHolder.setContext(previous); }
    }
    // Legacy fixture data uses explicit timestamps to exercise exact SQL ties; no production seeding exists.
    private void legacyCompleted(UUID id, boolean accepted, Instant time) {
        tx.executeWithoutResult(status -> {
            var s = submissions.findById(id).orElseThrow(); var report = report(id, UUID.randomUUID(), accepted);
            var testResults = context.getBean(TestCaseResultRepository.class);
            for (var test : report.tests()) testResults.saveAndFlush(new TestCaseResult(s,
                    test.testCaseId().equals(fixture.publicTest().getId()) ? fixture.publicTest() : fixture.hiddenTest(),
                    test.status(), test.actualOutput(), test.executionTimeMs(), test.awardedPoints()));
            context.getBean(SubmissionResultRepository.class).saveAndFlush(new SubmissionResult(s, report.totalScore(), report.passedTests(), 2, 12L, report.verdict()));
            s.setStatus(SubmissionStatus.COMPLETED); s.setCompletedAt(time);
        });
    }
    private static List<String> concurrent(Runnable first, Runnable second) throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<String>> futures = new ArrayList<>();
            for (var action : List.of(first, second)) futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try { action.run(); return "ok"; } catch (EvaluationConflictException conflict) { return "conflict"; }
            }));
            return List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS));
        }
    }
    private static User account(Role role) {
        String name = "u" + UUID.randomUUID().toString().replace("-", "");
        return context.getBean(UserRepository.class).saveAndFlush(new User(name, name + "@example.test", hash, role));
    }
    private static String login(User user) throws Exception {
        return body(send("POST", "/api/auth/login", null, Map.of("identifier", user.getUsername(), "password", PASSWORD)), 200).get("accessToken").asText();
    }
    private static JsonNode get(String path, String token, int expected) throws Exception { return body(send("GET", path, token, null), expected); }
    private static HttpResponse<String> send(String method, String path, String token, Object payload) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return HTTP.send(request.method(method, payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload))).build(), HttpResponse.BodyHandlers.ofString());
    }
    private static JsonNode body(HttpResponse<String> response, int expected) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expected); return JSON.readTree(response.body());
    }
    private static String resultPath(UUID id) { return "/api/submissions/" + id + "/result"; }
    private String boardPath() { return "/api/assignments/" + fixture.assignment() + "/leaderboard"; }
}

