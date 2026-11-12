package org.university.judge.api.persistence;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.validation.ConstraintViolationException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.university.judge.api.JudgeApiApplication;
import org.university.judge.api.support.TestJwtKeys;
import org.university.judge.api.assignment.domain.Assignment;
import org.university.judge.api.assignment.persistence.AssignmentRepository;
import org.university.judge.api.assignment.domain.AssignmentRevision;
import org.university.judge.api.assignment.persistence.AssignmentRevisionRepository;
import org.university.judge.api.assignment.domain.AssignmentStatus;
import org.university.judge.api.outbox.OutboxEvent;
import org.university.judge.api.outbox.OutboxEventRepository;
import org.university.judge.api.result.SubmissionResult;
import org.university.judge.api.result.SubmissionResultRepository;
import org.university.judge.api.result.TestCaseResult;
import org.university.judge.api.result.TestCaseResultRepository;
import org.university.judge.api.result.Verdict;
import org.university.judge.api.submission.domain.Submission;
import org.university.judge.api.submission.persistence.SubmissionRepository;
import org.university.judge.api.submission.domain.SubmissionStatus;
import org.university.judge.api.testcase.domain.TestCase;
import org.university.judge.api.testcase.persistence.TestCaseRepository;
import org.university.judge.api.user.Role;
import org.university.judge.api.user.User;
import org.university.judge.api.user.UserRepository;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

@Testcontainers
class PersistenceIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withPassword(UUID.randomUUID().toString());

    private static ConfigurableApplicationContext context;
    private static TransactionTemplate transaction;
    private static EntityManager em;
    private static JdbcTemplate jdbc;
    private static UserRepository users;
    private static AssignmentRepository assignments;
    private static AssignmentRevisionRepository revisions;
    private static TestCaseRepository testCases;
    private static SubmissionRepository submissions;
    private static SubmissionResultRepository results;
    private static TestCaseResultRepository caseResults;
    private static OutboxEventRepository events;

    @BeforeAll
    static void startApplication() {
        // Direct context lifecycle preserves JUnit 5 compatibility with Spring 7.
        context = new SpringApplicationBuilder(JudgeApiApplication.class).run(TestJwtKeys.arguments(
                "--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.data.redis.password=unused-in-database-tests",
                "--management.health.redis.enabled=false",
                "--management.endpoint.health.group.readiness.include=readinessState,db"));
        transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        em = SharedEntityManagerCreator.createSharedEntityManager(context.getBean(EntityManagerFactory.class));
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        users = context.getBean(UserRepository.class);
        assignments = context.getBean(AssignmentRepository.class);
        revisions = context.getBean(AssignmentRevisionRepository.class);
        testCases = context.getBean(TestCaseRepository.class);
        submissions = context.getBean(SubmissionRepository.class);
        results = context.getBean(SubmissionResultRepository.class);
        caseResults = context.getBean(TestCaseResultRepository.class);
        events = context.getBean(OutboxEventRepository.class);
    }

    @AfterAll
    static void closeApplication() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void migrationsUpgradeAnExistingStageOneDatabaseAndValidateOnRepeat() {
        Flyway stageOne = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("stage_one_upgrade").defaultSchema("stage_one_upgrade").target("1").load();
        assertThat(stageOne.migrate().migrationsExecuted).isEqualTo(1);
        Flyway latest = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("stage_one_upgrade").defaultSchema("stage_one_upgrade").load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(9);
        latest.validate();
        assertThat(latest.migrate().migrationsExecuted).isZero();
        assertThat(context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'stage_one_upgrade' AND table_name <> 'flyway_schema_history'
                """, Integer.class)).isEqualTo(10);
    }

    @Test
    void tokenVersionMigrationPreservesExistingAccountsAndInvalidatesOnStatusChanges() {
        String schema = "stage_four_upgrade";
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).target("4").load().migrate();
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO stage_four_upgrade.users(id, username, email, password_hash, role) "
                + "VALUES (?, 'existing-student', 'existing@example.test', 'existing-test-hash', 'STUDENT')", id);
        Flyway upgraded = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).load();
        assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(6);
        upgraded.validate();
        assertThat(jdbc.queryForObject("SELECT password_hash FROM stage_four_upgrade.users WHERE id = ?", String.class, id))
                .isEqualTo("existing-test-hash");
        assertThat(jdbc.queryForObject("SELECT token_version FROM stage_four_upgrade.users WHERE id = ?", Long.class, id)).isZero();
        jdbc.update("UPDATE stage_four_upgrade.users SET enabled = false WHERE id = ?", id);
        assertThat(jdbc.queryForObject("SELECT token_version FROM stage_four_upgrade.users WHERE id = ?", Long.class, id)).isEqualTo(1L);
        jdbc.update("UPDATE stage_four_upgrade.users SET enabled = true WHERE id = ?", id);
        assertThat(jdbc.queryForObject("SELECT token_version FROM stage_four_upgrade.users WHERE id = ?", Long.class, id)).isEqualTo(2L);
    }

    @Test
    void repositoriesRoundTripEveryEntityWithDecimalScoresUtcTimestampsAndJsonb() {
        Fixture f = fixture(true);
        transaction.executeWithoutResult(tx -> {
            Submission submission = submissions.findById(f.submission()).orElseThrow();
            assertThat(submission.getStatus()).isEqualTo(SubmissionStatus.PENDING);
            assertThat(submission.getAttemptCount()).isZero();
            assertThat(submission.getStartedAt()).isNull();
            assertThat(submission.getSubmittedAt()).isBeforeOrEqualTo(Instant.now());
            assertThat(submission.getRevision().getDeadline()).isEqualTo(DEADLINE);
            assertThat(submission.getRevision().getMaximumScore()).isEqualByComparingTo("10.0000");

            results.save(new SubmissionResult(submission, new BigDecimal("10.0000"), 2, 2, 17L, Verdict.ACCEPTED));
            caseResults.save(new TestCaseResult(submission, testCases.getReferenceById(f.firstCase()),
                    Verdict.ACCEPTED, "42\n", 8L, new BigDecimal("4.2500")));
            caseResults.save(new TestCaseResult(submission, testCases.getReferenceById(f.secondCase()),
                    Verdict.ACCEPTED, "", 9L, new BigDecimal("5.7500")));
            OutboxEvent event = events.save(new OutboxEvent(submission.getId(), "submission.created", 1,
                    Map.of("submissionId", submission.getId().toString(), "configuration", Map.of("language", "JAVA"))));
            submission.setStatus(SubmissionStatus.COMPLETED);
            submission.setStartedAt(submission.getSubmittedAt().plusSeconds(1));
            submission.setCompletedAt(submission.getSubmittedAt().plusSeconds(2));
            submission.setAttemptCount(1);
            em.flush();
            UUID eventId = event.getId();
            em.clear();

            SubmissionResult saved = results.findBySubmissionId(f.submission()).orElseThrow();
            assertThat(saved.getTotalScore()).isEqualByComparingTo("10");
            assertThat(saved.getMaximumScore()).isEqualByComparingTo("10");
            assertThat(saved.getVerdict()).isEqualTo(Verdict.ACCEPTED);
            assertThat(saved.getExecutionTimeMs()).isEqualTo(17L);
            assertThat(saved.getCreatedAt()).isNotNull();
            assertThat(caseResults.findBySubmissionIdOrderByTestCaseExecutionOrder(f.submission()))
                    .extracting(TestCaseResult::getAwardedPoints)
                    .containsExactly(new BigDecimal("4.2500"), new BigDecimal("5.7500"));
            assertThat(testCases.findByRevisionIdOrderByExecutionOrder(f.revision()))
                    .extracting(TestCase::getExecutionOrder).containsExactly(0, 1);
            assertThat(submissions.findByStudentIdOrderBySubmittedAtDesc(f.student(), PageRequest.of(0, 10)))
                    .extracting(Submission::getId).containsExactly(f.submission());
            assertThat(submissions.findByAssignmentIdOrderBySubmittedAtDesc(f.assignment(), PageRequest.of(0, 10)))
                    .extracting(Submission::getId).containsExactly(f.submission());
            assertThat(assignments.findByTeacherId(f.teacher(), PageRequest.of(0, 10)))
                    .extracting(Assignment::getId).containsExactly(f.assignment());
            assertThat(revisions.findByAssignmentIdAndRevisionNumber(f.assignment(), 1)).isPresent();
            OutboxEvent storedEvent = events.findById(eventId).orElseThrow();
            assertThat(storedEvent.getPayload()).containsEntry("configuration", Map.of("language", "JAVA"));
            assertThat(events.findByPublishedAtIsNullOrderByCreatedAtAscIdAsc(PageRequest.of(0, 100)))
                    .extracting(OutboxEvent::getId).contains(eventId);
            storedEvent.setAttemptCount(1);
            storedEvent.setPublishedAt(Instant.now());
            em.flush();
            assertThat(events.findByPublishedAtIsNullOrderByCreatedAtAscIdAsc(PageRequest.of(0, 100)))
                    .extracting(OutboxEvent::getId).doesNotContain(eventId);
        });
        assertThat(jdbc.queryForObject("SELECT pg_typeof(payload)::text FROM outbox_events LIMIT 1", String.class))
                .isEqualTo("jsonb");
    }

    @Test
    void userRepositoriesAuditUpdatesAndDoNotSerializePasswordHashes() {
        transaction.executeWithoutResult(tx -> {
            for (Role role : Role.values()) {
                User user = users.saveAndFlush(newUser(role));
                String name = user.getUsername();
                String email = user.getEmail();
                Instant createdAt = user.getCreatedAt();
                em.clear();
                User loaded = users.findByUsername(name).orElseThrow();
                assertThat(loaded.getRole()).isEqualTo(role);
                assertThat(loaded.isEnabled()).isTrue();
                assertThat(users.findByEmail(email)).isPresent();
                assertThat(users.countByRoleAndEnabledTrue(role)).isPositive();
                assertThat(JsonMapper.builder().build().writeValueAsString(loaded))
                        .doesNotContain("passwordHash", loaded.getPasswordHash());
                if (role == Role.ADMIN) {
                    continue;
                }
                loaded.setEnabled(false);
                em.flush();
                em.clear();
                User updated = users.findByUsername(name).orElseThrow();
                assertThat(updated.isEnabled()).isFalse();
                assertThat(updated.getUpdatedAt()).isAfterOrEqualTo(createdAt);
                assertThat(updated.getCreatedAt()).isEqualTo(createdAt);
            }
        });
        assertThatThrownBy(() -> transaction.executeWithoutResult(tx ->
                users.saveAndFlush(new User(" ", "invalid-email", "test-only-hash", Role.STUDENT))))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void enumValuesRoundTripUsingTheirNames() {
        Fixture f = fixture(true);
        for (AssignmentStatus status : AssignmentStatus.values()) {
            transaction.executeWithoutResult(tx -> assignments.findById(f.assignment()).orElseThrow().setStatus(status));
            assertThat(assignments.findById(f.assignment()).orElseThrow().getStatus()).isEqualTo(status);
        }
        for (SubmissionStatus status : SubmissionStatus.values()) {
            transaction.executeWithoutResult(tx -> submissions.findById(f.submission()).orElseThrow().setStatus(status));
            assertThat(submissions.findById(f.submission()).orElseThrow().getStatus()).isEqualTo(status);
        }
        jdbc.update(INSERT_RESULT, UUID.randomUUID(), f.submission());
        jdbc.update(INSERT_CASE_RESULT, UUID.randomUUID(), f.submission(), f.firstCase(), f.revision());
        for (Verdict verdict : Verdict.values()) {
            jdbc.update("UPDATE submission_results SET verdict = ? WHERE submission_id = ?", verdict.name(), f.submission());
            jdbc.update("UPDATE test_case_results SET status = ? WHERE submission_id = ?", verdict.name(), f.submission());
            assertThat(results.findBySubmissionId(f.submission()).orElseThrow().getVerdict()).isEqualTo(verdict);
            assertThat(caseResults.findBySubmissionIdOrderByTestCaseExecutionOrder(f.submission()).getFirst().getStatus())
                    .isEqualTo(verdict);
        }
    }

    @Test
    void databaseDefaultsAndNullableEvaluationFieldsRoundTrip() {
        Fixture f = fixture(false);
        UUID userId = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id, username, email, password_hash, role) VALUES (?, ?, ?, ?, 'STUDENT')",
                userId, userId.toString(), userId + "@example.test", "test-only-hash");
        assertThat(users.findById(userId).orElseThrow().isEnabled()).isTrue();
        UUID caseId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO test_cases(id, assignment_id, assignment_revision_id, input_data, expected_output, points, execution_order)
                VALUES (?, ?, ?, '', '', 1, 2)
                """, caseId, f.assignment(), f.revision());
        assertThat(testCases.findById(caseId).orElseThrow().isHidden()).isTrue();
        // Stage 6 requires positive case points when sealing; retain the same total.
        jdbc.update("UPDATE test_cases SET points = points - 1 WHERE id = ?", f.secondCase());
        jdbc.update("UPDATE assignment_revisions SET sealed_at = clock_timestamp() WHERE id = ?", f.revision());
        UUID submissionId = UUID.randomUUID();
        insertSubmission(submissionId, f.assignment(), f.revision(), f.student());
        assertThat(submissions.findById(submissionId).orElseThrow().getAttemptCount()).isZero();
        jdbc.update("""
                INSERT INTO test_case_results(id, submission_id, test_case_id, assignment_revision_id, status, awarded_points)
                VALUES (?, ?, ?, ?, 'INTERNAL_ERROR', 0)
                """, UUID.randomUUID(), submissionId, caseId, f.revision());
        TestCaseResult result = caseResults.findBySubmissionIdOrderByTestCaseExecutionOrder(submissionId).getFirst();
        assertThat(result.getActualOutput()).isNull();
        assertThat(result.getExecutionTimeMs()).isNull();
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO outbox_events(id, aggregate_id, event_type, event_version, payload)
                VALUES (?, ?, 'submission.created', 1, '{"nullable":null,"nested":[1,2]}'::jsonb)
                """, eventId, submissionId);
        OutboxEvent event = events.findById(eventId).orElseThrow();
        assertThat(event.getAttemptCount()).isZero();
        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getPayload()).containsEntry("nullable", null);
    }

    @Test
    void foreignKeysRejectMissingParentsAndMismatchedAssignments() {
        Fixture f = fixture(true);
        Fixture other = fixture(true);
        rejected("23503", "UPDATE assignments SET teacher_id = ? WHERE id = ?", UUID.randomUUID(), f.assignment());
        rejected("23503", """
                INSERT INTO assignment_revisions(id, assignment_id, revision_number, title, description, maximum_score, deadline)
                VALUES (?, ?, 1, 'Title', '', 10, now())
                """, UUID.randomUUID(), UUID.randomUUID());
        rejected("23503", INSERT_SUBMISSION, UUID.randomUUID(), f.assignment(), other.revision(), f.student());
        rejected("23503", INSERT_SUBMISSION, UUID.randomUUID(), f.assignment(), f.revision(), UUID.randomUUID());
        rejected("23503", INSERT_SUBMISSION, UUID.randomUUID(), f.assignment(), UUID.randomUUID(), f.student());
        rejected("23503", INSERT_CASE_RESULT, UUID.randomUUID(), f.submission(), other.firstCase(), f.revision());
        rejected("23503", INSERT_CASE_RESULT, UUID.randomUUID(), UUID.randomUUID(), f.firstCase(), f.revision());
        rejected("23503", INSERT_RESULT, UUID.randomUUID(), UUID.randomUUID());
        Fixture draft = fixture(false);
        rejected("23503", """
                INSERT INTO test_cases(id, assignment_id, assignment_revision_id, input_data, expected_output, points, execution_order)
                VALUES (?, ?, ?, '', '', 0, 2)
                """, UUID.randomUUID(), other.assignment(), draft.revision());
    }

    @Test
    void uniqueConstraintsRejectDuplicateUsersRevisionNumbersTestOrderAndResults() {
        Fixture f = fixture(true);
        User student = users.findById(f.student()).orElseThrow();
        rejected("23505", "INSERT INTO users(id, username, email, password_hash, role) VALUES (?, ?, ?, 'hash', 'STUDENT')",
                UUID.randomUUID(), student.getUsername(), UUID.randomUUID() + "@example.test");
        rejected("23505", "INSERT INTO users(id, username, email, password_hash, role) VALUES (?, ?, ?, 'hash', 'STUDENT')",
                UUID.randomUUID(), UUID.randomUUID().toString(), student.getEmail());
        rejected("23505", """
                INSERT INTO assignment_revisions(id, assignment_id, revision_number, title, description, maximum_score, deadline)
                VALUES (?, ?, 1, 'Title', '', 10, now())
                """, UUID.randomUUID(), f.assignment());
        Fixture draft = fixture(false);
        rejected("23505", """
                INSERT INTO test_cases(id, assignment_id, assignment_revision_id, input_data, expected_output, points, execution_order)
                VALUES (?, ?, ?, '', '', 0, 0)
                """, UUID.randomUUID(), draft.assignment(), draft.revision());
        jdbc.update(INSERT_RESULT, UUID.randomUUID(), f.submission());
        rejected("23505", INSERT_RESULT, UUID.randomUUID(), f.submission());
        jdbc.update(INSERT_CASE_RESULT, UUID.randomUUID(), f.submission(), f.firstCase(), f.revision());
        rejected("23505", INSERT_CASE_RESULT, UUID.randomUUID(), f.submission(), f.firstCase(), f.revision());
    }

    @Test
    void checksRejectInvalidRolesStatusesScoresCountersTimestampsAndPayloads() {
        Fixture f = fixture(true);
        Fixture draft = fixture(false);
        rejected("23514", "UPDATE users SET role = 'ROOT' WHERE id = ?", f.student());
        rejected("23502", "UPDATE users SET password_hash = NULL WHERE id = ?", f.student());
        rejected("23514", "UPDATE assignments SET status = 'INVALID' WHERE id = ?", f.assignment());
        rejected("23514", "UPDATE assignments SET maximum_score = -1 WHERE id = ?", f.assignment());
        rejected("23514", "UPDATE assignments SET maximum_score = 'NaN' WHERE id = ?", f.assignment());
        rejected("23514", "UPDATE assignment_revisions SET revision_number = 0 WHERE id = ?", draft.revision());
        rejected("23514", "UPDATE test_cases SET points = -1 WHERE id = ?", draft.firstCase());
        rejected("23514", "UPDATE test_cases SET execution_order = -1 WHERE id = ?", draft.firstCase());
        rejected("23514", "UPDATE submissions SET status = 'ACCEPTED' WHERE id = ?", f.submission());
        rejected("23514", "UPDATE submissions SET attempt_count = -1 WHERE id = ?", f.submission());
        rejected("23514", "UPDATE submissions SET started_at = submitted_at - interval '1 second' WHERE id = ?", f.submission());
        rejected("23514", "UPDATE submissions SET completed_at = submitted_at - interval '1 second' WHERE id = ?", f.submission());
        jdbc.update(INSERT_RESULT, UUID.randomUUID(), f.submission());
        rejected("23514", "UPDATE submission_results SET total_score = 11 WHERE submission_id = ?", f.submission());
        rejected("23514", "UPDATE submission_results SET passed_tests = 3 WHERE submission_id = ?", f.submission());
        rejected("23514", "UPDATE submission_results SET execution_time_ms = -1 WHERE submission_id = ?", f.submission());
        rejected("23514", "UPDATE submission_results SET verdict = 'UNKNOWN' WHERE submission_id = ?", f.submission());
        jdbc.update(INSERT_CASE_RESULT, UUID.randomUUID(), f.submission(), f.firstCase(), f.revision());
        rejected("23514", "UPDATE test_case_results SET awarded_points = -1 WHERE submission_id = ?", f.submission());
        rejected("23514", "UPDATE test_case_results SET awarded_points = 5 WHERE submission_id = ?", f.submission());
        for (String values : new String[]{"0, '{}'", "1, '[]'", "1, 'null'"}) {
            rejected("23514", "INSERT INTO outbox_events(id, aggregate_id, event_type, event_version, payload) "
                    + "VALUES (?, ?, 'submission.created', " + values + ")", UUID.randomUUID(), f.submission());
        }
        UUID eventId = UUID.randomUUID();
        jdbc.update("INSERT INTO outbox_events(id, aggregate_id, event_type, event_version, payload) "
                + "VALUES (?, ?, 'submission.created', 1, '{}')", eventId, f.submission());
        rejected("23514", "UPDATE outbox_events SET attempt_count = -1 WHERE id = ?", eventId);
        rejected("23514", "UPDATE outbox_events SET published_at = created_at - interval '1 second' WHERE id = ?", eventId);
    }

    @Test
    void gradingHistorySurvivesAssignmentEditsAndANewRevision() {
        Fixture f = fixture(true);
        jdbc.update(INSERT_RESULT, UUID.randomUUID(), f.submission());
        UUID newRevision = transaction.execute(tx -> {
            Assignment assignment = assignments.findById(f.assignment()).orElseThrow();
            assignment.updateDetails("New title", "New description", new BigDecimal("20"), DEADLINE.plusSeconds(86400));
            assignment.setStatus(AssignmentStatus.PUBLISHED);
            AssignmentRevision revision = revisions.saveAndFlush(new AssignmentRevision(assignment, 2));
            testCases.saveAndFlush(new TestCase(revision, "new input", "new output", new BigDecimal("20"), true, 0));
            revision.seal();
            em.flush();
            return revision.getId();
        });
        transaction.executeWithoutResult(tx -> {
            Submission submission = submissions.findById(f.submission()).orElseThrow();
            assertThat(submission.getRevision().getId()).isEqualTo(f.revision());
            assertThat(submission.getRevision().getTitle()).isEqualTo("Original title");
            assertThat(submission.getRevision().getDeadline()).isEqualTo(DEADLINE);
            assertThat(submission.getRevision().getMaximumScore()).isEqualByComparingTo("10");
            assertThat(results.findBySubmissionId(f.submission()).orElseThrow().getMaximumScore()).isEqualByComparingTo("10");
            assertThat(testCases.findById(f.firstCase()).orElseThrow().getExpectedOutput()).isEqualTo("42\n");
            assertThat(revisions.findFirstByAssignmentIdAndSealedAtIsNotNullOrderByRevisionNumberDesc(f.assignment())
                    .orElseThrow().getId()).isEqualTo(newRevision);
            assertThatThrownBy(() -> testCases.findById(f.firstCase()).orElseThrow()
                    .updateDetails("changed", "changed", BigDecimal.ZERO, false, 0)).isInstanceOf(IllegalStateException.class);
        });
        rejected("23514", "UPDATE submissions SET assignment_revision_id = ? WHERE id = ?", newRevision, f.submission());
        rejected("23514", "UPDATE submissions SET source_file_key = 'replacement' WHERE id = ?", f.submission());
        rejected("23514", "UPDATE submission_results SET maximum_score = 20 WHERE submission_id = ?", f.submission());
        rejected("23514", "UPDATE submission_results SET total_tests = 3 WHERE submission_id = ?", f.submission());
        UUID newCase = testCases.findByRevisionIdOrderByExecutionOrder(newRevision).getFirst().getId();
        rejected("23503", INSERT_CASE_RESULT, UUID.randomUUID(), f.submission(), newCase, f.revision());
    }

    @Test
    void sealedRevisionsRejectAllConfigurationMutationsAndDraftsCannotAcceptSubmissions() {
        Fixture f = fixture(true);
        rejected("23514", "UPDATE assignment_revisions SET maximum_score = 20 WHERE id = ?", f.revision());
        rejected("23514", "UPDATE assignment_revisions SET sealed_at = NULL WHERE id = ?", f.revision());
        rejected("23514", "UPDATE test_cases SET expected_output = 'changed' WHERE id = ?", f.firstCase());
        rejected("23514", "UPDATE test_cases SET points = 0 WHERE id = ?", f.firstCase());
        rejected("23514", "DELETE FROM test_cases WHERE id = ?", f.firstCase());
        rejected("23514", "DELETE FROM assignment_revisions WHERE id = ?", f.revision());
        rejected("23514", """
                INSERT INTO test_cases(id, assignment_id, assignment_revision_id, input_data, expected_output, points, execution_order)
                VALUES (?, ?, ?, '', '', 0, 2)
                """, UUID.randomUUID(), f.assignment(), f.revision());
        Fixture draft = fixture(false);
        rejected("23514", INSERT_SUBMISSION, UUID.randomUUID(), draft.assignment(), draft.revision(), draft.student());
        jdbc.update("UPDATE test_cases SET points = 0 WHERE id = ?", draft.firstCase());
        rejected("23514", "UPDATE assignment_revisions SET sealed_at = clock_timestamp() WHERE id = ?", draft.revision());
        jdbc.update("DELETE FROM test_cases WHERE assignment_revision_id = ?", draft.revision());
        rejected("23514", "UPDATE assignment_revisions SET sealed_at = clock_timestamp() WHERE id = ?", draft.revision());
    }

    @Test
    void historicalParentsCannotBeDeletedAndForeignKeysNeverCascade() {
        Fixture f = fixture(true);
        jdbc.update(INSERT_RESULT, UUID.randomUUID(), f.submission());
        jdbc.update(INSERT_CASE_RESULT, UUID.randomUUID(), f.submission(), f.firstCase(), f.revision());
        rejected("23503", "DELETE FROM users WHERE id = ?", f.student());
        rejected("23503", "DELETE FROM users WHERE id = ?", f.teacher());
        rejected("23503", "DELETE FROM assignments WHERE id = ?", f.assignment());
        rejected("23503", "DELETE FROM submissions WHERE id = ?", f.submission());
        assertThat(jdbc.queryForList("SELECT delete_rule FROM information_schema.referential_constraints "
                + "WHERE constraint_schema = 'public'", String.class)).isNotEmpty().containsOnly("RESTRICT");
    }

    @Test
    void sealingAndTestCaseEditsSerializeInBothDirections() throws Exception {
        Fixture f = fixture(false);
        DataSource source = context.getBean(DataSource.class);
        try (Connection editor = source.getConnection(); Connection sealer = source.getConnection()) {
            editor.setAutoCommit(false);
            editor.createStatement().executeUpdate("UPDATE test_cases SET expected_output = 'latest' WHERE id = '" + f.firstCase() + "'");
            sealer.createStatement().execute("SET lock_timeout = '200ms'");
            String seal = "UPDATE assignment_revisions SET sealed_at = clock_timestamp() WHERE id = '" + f.revision() + "'";
            assertThatThrownBy(() -> sealer.createStatement().executeUpdate(seal))
                    .isInstanceOf(SQLException.class).extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("55P03");
            editor.commit();
            sealer.setAutoCommit(false);
            sealer.createStatement().executeUpdate(seal);
            editor.setAutoCommit(true);
            editor.createStatement().execute("SET lock_timeout = '200ms'");
            String edit = "UPDATE test_cases SET expected_output = 'too late' WHERE id = '" + f.firstCase() + "'";
            assertThatThrownBy(() -> editor.createStatement().executeUpdate(edit))
                    .isInstanceOf(SQLException.class).extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("55P03");
            sealer.commit();
            assertThatThrownBy(() -> editor.createStatement().executeUpdate(edit))
                    .isInstanceOf(SQLException.class).extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("23514");
            // These are session settings on pooled connections; restore them.
            editor.createStatement().execute("RESET lock_timeout");
            sealer.setAutoCommit(true);
            sealer.createStatement().execute("RESET lock_timeout");
        }
        assertThat(testCases.findById(f.firstCase()).orElseThrow().getExpectedOutput()).isEqualTo("latest");
    }

    private static final Instant DEADLINE = Instant.parse("2030-06-01T12:34:56.123456Z");
    private static final String INSERT_SUBMISSION = """
            INSERT INTO submissions(id, assignment_id, assignment_revision_id, student_id, source_file_key,
                original_filename, programming_language, status)
            VALUES (?, ?, ?, ?, 'test/source.java', 'Main.java', 'JAVA', 'PENDING')
            """;
    private static final String INSERT_RESULT = """
            INSERT INTO submission_results(id, submission_id, total_score, maximum_score, passed_tests, total_tests, verdict)
            VALUES (?, ?, 10, 10, 2, 2, 'ACCEPTED')
            """;
    private static final String INSERT_CASE_RESULT = """
            INSERT INTO test_case_results(id, submission_id, test_case_id, assignment_revision_id, status, awarded_points)
            VALUES (?, ?, ?, ?, 'WRONG_ANSWER', 0)
            """;

    private static void insertSubmission(UUID id, UUID assignment, UUID revision, UUID student) {
        jdbc.update(INSERT_SUBMISSION, id, assignment, revision, student);
    }

    private static void rejected(String state, String sql, Object... args) {
        Throwable error = catchThrowable(() -> jdbc.update(sql, args));
        assertThat(error).as("SQL must be rejected: %s", sql).isInstanceOf(DataAccessException.class);
        Throwable cause = error;
        while (!(cause instanceof SQLException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        assertThat(cause).isInstanceOf(SQLException.class);
        assertThat(((SQLException) cause).getSQLState()).as("SQLSTATE for %s", sql).isEqualTo(state);
    }

    private static User newUser(Role role) {
        String name = UUID.randomUUID().toString();
        return new User(name, name + "@example.test", "test-only-hash-" + name, role);
    }

    private static Fixture fixture(boolean seal) {
        return transaction.execute(tx -> {
            User teacher = users.save(newUser(Role.TEACHER));
            User student = users.save(newUser(Role.STUDENT));
            Assignment assignment = assignments.save(new Assignment(teacher, "Original title", "Original description",
                    new BigDecimal("10.0000"), DEADLINE));
            AssignmentRevision revision = revisions.save(new AssignmentRevision(assignment, 1));
            TestCase first = testCases.save(new TestCase(revision, "21\n", "42\n", new BigDecimal("4.2500"), true, 0));
            TestCase second = testCases.save(new TestCase(revision, "", "", new BigDecimal("5.7500"), false, 1));
            em.flush();
            Submission submission = null;
            if (seal) {
                revision.seal();
                em.flush();
                submission = submissions.saveAndFlush(new Submission(revision, student,
                        "submissions/" + UUID.randomUUID() + "/Main.java", "Main.java", "JAVA"));
            }
            return new Fixture(teacher.getId(), student.getId(), assignment.getId(), revision.getId(),
                    first.getId(), second.getId(), submission == null ? null : submission.getId());
        });
    }

    private record Fixture(UUID teacher, UUID student, UUID assignment, UUID revision,
                           UUID firstCase, UUID secondCase, UUID submission) {
    }
}
