package org.university.judge.api.result.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.university.judge.api.result.Verdict;

/** Internal report from an authenticated evaluator; never an HTTP request DTO. */
public record EvaluationCompletion(UUID submissionId, UUID attemptId, Verdict verdict, BigDecimal totalScore,
        int passedTests, int totalTests, Long executionTimeMs, List<TestOutcome> tests) {
    public EvaluationCompletion { if (tests != null) tests = List.copyOf(tests); }
    public record TestOutcome(UUID testCaseId, Verdict status, BigDecimal awardedPoints, Long executionTimeMs, String actualOutput) { }
}

