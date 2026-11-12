package org.university.judge.api.result.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.university.judge.api.result.Verdict;
import org.university.judge.api.submission.domain.SubmissionStatus;

public record EvaluationResponse(UUID submissionId, SubmissionStatus status, Verdict verdict,
        BigDecimal totalScore, BigDecimal maximumScore, Integer passedTests, Integer totalTests,
        Long executionTimeMs, Instant submittedAt, Instant completedAt, List<TestResultResponse> testResults) { }

