package org.university.judge.api.result.application;

import java.io.DataOutputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.university.judge.api.result.*;
import org.university.judge.api.submission.domain.Submission;
import org.university.judge.api.submission.domain.SubmissionStatus;
import org.university.judge.api.submission.persistence.SubmissionRepository;
import org.university.judge.api.submission.persistence.SubmissionRequestStore;
import org.university.judge.api.testcase.domain.TestCase;
import org.university.judge.api.testcase.persistence.TestCaseRepository;

/**
 * Internal completion boundary. Local JWT authentication NEVER grants this authority.
 * The internal worker transport verifies a separate evaluator identity before invoking it.
 */
@Service
@PreAuthorize("hasAuthority('EVALUATION_WRITE')")
@Transactional
public class EvaluationCompletionService {
    public record Attempt(UUID submissionId, UUID attemptId, UUID revisionId, long version) { }
    private final SubmissionRepository submissions;
    private final SubmissionResultRepository results;
    private final TestCaseResultRepository outcomes;
    private final TestCaseRepository tests;
    private final SubmissionRequestStore clock;

    public EvaluationCompletionService(SubmissionRepository submissions, SubmissionResultRepository results,
            TestCaseResultRepository outcomes, TestCaseRepository tests, SubmissionRequestStore clock) {
        this.submissions = submissions; this.results = results; this.outcomes = outcomes; this.tests = tests; this.clock = clock;
    }

    public Attempt begin(UUID submissionId, UUID attemptId, long expectedVersion) {
        require(attemptId != null && submissionId != null, "Submission and attempt IDs are required");
        var submission = locked(submissionId);
        if (attemptId.equals(submission.getEvaluationAttemptId())) return attempt(submission);
        require(submission.getVersion() == expectedVersion, "Stale submission version");
        require(submission.getStatus() == SubmissionStatus.PENDING || submission.getStatus() == SubmissionStatus.RUNNING,
                "Submission cannot start an evaluation");
        require(results.findBySubmissionId(submissionId).isEmpty() && outcomes.findBySubmissionIdOrderByTestCaseExecutionOrder(submissionId).isEmpty(),
                "Submission already has result data");
        submission.beginEvaluation(attemptId, clock.now());
        submissions.flush();
        return attempt(submission);
    }

    public void complete(EvaluationCompletion report) {
        require(report != null && report.submissionId() != null && report.attemptId() != null, "Submission and attempt IDs are required");
        var submission = locked(report.submissionId());
        require(report.attemptId().equals(submission.getEvaluationAttemptId()), "Stale evaluation attempt");
        var configured = tests.findByRevisionIdOrderByExecutionOrder(submission.getRevision().getId());
        validate(report, configured);
        String fingerprint = fingerprint(report);
        if (submission.getStatus() == SubmissionStatus.COMPLETED) {
            require(fingerprint.equals(submission.getEvaluationFingerprint()), "Conflicting completion replay");
            return;
        }
        require(submission.getStatus() == SubmissionStatus.RUNNING, "Submission is not running");
        require(results.findBySubmissionId(submission.getId()).isEmpty() && outcomes.findBySubmissionIdOrderByTestCaseExecutionOrder(submission.getId()).isEmpty(),
                "Submission already has result data");
        Map<UUID, TestCase> byId = new HashMap<>();
        configured.forEach(test -> byId.put(test.getId(), test));
        outcomes.saveAllAndFlush(report.tests().stream().map(test -> new TestCaseResult(submission, byId.get(test.testCaseId()),
                test.status(), test.actualOutput(), test.executionTimeMs(), test.awardedPoints())).toList());
        results.saveAndFlush(new SubmissionResult(submission, report.totalScore(), report.passedTests(), report.totalTests(), report.executionTimeMs(), report.verdict()));
        submission.completeEvaluation(fingerprint, clock.now());
        submissions.flush();
    }

    private Submission locked(UUID id) {
        return submissions.findByIdForUpdate(id).orElseThrow(() -> new EvaluationConflictException("Submission not found"));
    }
    private Attempt attempt(Submission submission) {
        return new Attempt(submission.getId(), submission.getEvaluationAttemptId(), submission.getRevision().getId(), submission.getVersion());
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new EvaluationConflictException(message);
    }
    private static void valid(boolean condition, String message) {
        if (!condition) throw new EvaluationValidationException(message);
    }
    private static boolean score(BigDecimal value) {
        return value != null && value.signum() >= 0 && value.stripTrailingZeros().scale() <= 4
                && value.compareTo(new BigDecimal("1000000000000000")) < 0;
    }
    private static boolean duration(Long value) { return value == null || value >= 0; }

    private static void validate(EvaluationCompletion report, List<TestCase> configured) {
        valid(report.verdict() != null && report.tests() != null && score(report.totalScore()) && duration(report.executionTimeMs()), "Invalid summary");
        valid(report.totalTests() == configured.size() && !configured.isEmpty(), "Test count must match the sealed revision");
        Map<UUID, TestCase> remaining = new HashMap<>();
        configured.forEach(test -> remaining.put(test.getId(), test));
        BigDecimal sum = BigDecimal.ZERO;
        int passed = 0;
        boolean internal = false;
        for (var test : report.tests()) {
            var definition = remaining.remove(test.testCaseId());
            valid(definition != null, "Duplicate test or test outside the submission revision");
            valid(test.status() != null && score(test.awardedPoints()) && duration(test.executionTimeMs()), "Invalid test outcome");
            valid(test.actualOutput() == null || (test.actualOutput().indexOf(0) < 0
                    && test.actualOutput().getBytes(StandardCharsets.UTF_8).length <= 65536), "Output exceeds the 64 KiB limit or contains NUL");
            boolean accepted = test.status() == Verdict.ACCEPTED;
            valid(test.awardedPoints().compareTo(accepted ? definition.getPoints() : BigDecimal.ZERO) == 0,
                    "Accepted tests earn their configured points; other outcomes earn zero");
            if (accepted) passed++;
            if (test.status() == Verdict.INTERNAL_ERROR) internal = true;
            sum = sum.add(test.awardedPoints());
        }
        valid(report.totalScore().compareTo(sum) == 0 && report.passedTests() == passed, "Summary does not match reported tests");
        boolean compile = report.verdict() == Verdict.COMPILATION_ERROR;
        boolean failed = report.verdict() == Verdict.INTERNAL_ERROR;
        valid(!internal || failed, "Internal test errors require an internal-error summary");
        if (compile) valid(report.tests().isEmpty() && report.totalScore().signum() == 0, "Compilation failure cannot contain executed tests");
        else if (!failed) valid(remaining.isEmpty(), "Every configured test must be reported");
        if (report.verdict() == Verdict.ACCEPTED) valid(passed == configured.size(), "Accepted summary requires all tests to pass");
        else if (!failed && !compile) {
            valid(passed < configured.size() && report.tests().stream().anyMatch(test -> test.status() == report.verdict()),
                    "Verdict must describe a reported failure");
        }
    }

    /** Length-prefixed canonical fields; test ordering and decimal scale do not affect replay equivalence. */
    private static String fingerprint(EvaluationCompletion report) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var output = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
                field(output, report.submissionId()); field(output, report.attemptId()); field(output, report.verdict());
                field(output, report.totalScore().stripTrailingZeros().toPlainString());
                field(output, report.passedTests()); field(output, report.totalTests()); field(output, report.executionTimeMs());
                for (var test : report.tests().stream().sorted(Comparator.comparing(EvaluationCompletion.TestOutcome::testCaseId)).toList()) {
                    field(output, test.testCaseId()); field(output, test.status()); field(output, test.awardedPoints().stripTrailingZeros().toPlainString());
                    field(output, test.executionTimeMs()); field(output, test.actualOutput());
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void field(DataOutputStream output, Object value) throws java.io.IOException {
        if (value == null) { output.writeInt(-1); return; }
        byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length); output.write(bytes);
    }
}

