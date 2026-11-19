package org.university.judge.worker.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.university.judge.worker.grading.Verdict;

/** Mirrors EvaluationCompletion in judge-api, which validates every field before storing it. */
public record EvaluationReport(
        UUID submissionId,
        UUID attemptId,
        Verdict verdict,
        BigDecimal totalScore,
        int passedTests,
        int totalTests,
        Long executionTimeMs,
        List<TestOutcome> tests) {

    public EvaluationReport {
        tests = List.copyOf(tests);
    }

    /** A report the API accepts for an evaluation that could not be carried out; excluded from ranking. */
    public static EvaluationReport internalError(JudgeJob job) {
        return new EvaluationReport(job.submissionId(), job.attemptId(), Verdict.INTERNAL_ERROR, BigDecimal.ZERO, 0,
                job.tests().size(), null, List.of());
    }
}
