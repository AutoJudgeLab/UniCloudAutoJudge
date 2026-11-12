package org.university.judge.api.result.application;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.university.judge.api.result.SubmissionResultRepository;
import org.university.judge.api.result.TestCaseResultRepository;
import org.university.judge.api.result.api.EvaluationResponse;
import org.university.judge.api.result.api.TestResultResponse;
import org.university.judge.api.submission.application.SubmissionQueries;
import org.university.judge.api.submission.application.SubmissionAccess;
import org.university.judge.api.submission.domain.SubmissionStatus;
import org.university.judge.api.user.Role;

@Service
@PreAuthorize("isAuthenticated()")
public class EvaluationQueries {
    private final SubmissionQueries submissions;
    private final SubmissionAccess access;
    private final SubmissionResultRepository results;
    private final TestCaseResultRepository tests;
    public EvaluationQueries(SubmissionQueries submissions, SubmissionAccess access, SubmissionResultRepository results, TestCaseResultRepository tests) {
        this.submissions = submissions; this.access = access; this.results = results; this.tests = tests;
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public EvaluationResponse get(UUID id) {
        var submission = submissions.get(id); // Reuses the same ownership boundary as submission detail.
        var result = submission.status() == SubmissionStatus.COMPLETED ? results.findBySubmissionId(id).orElse(null) : null;
        if (result == null) return new EvaluationResponse(id, submission.status(), null, null, null, null, null, null,
                submission.submittedAt(), submission.completedAt(), null);
        boolean privileged = access.actor(false, false).getRole() != Role.STUDENT;
        var testResults = tests.findBySubmissionIdOrderByTestCaseExecutionOrder(id).stream()
                .map(test -> TestResultResponse.from(test, privileged)).toList();
        return new EvaluationResponse(id, submission.status(), result.getVerdict(), result.getTotalScore(), result.getMaximumScore(),
                result.getPassedTests(), result.getTotalTests(), result.getExecutionTimeMs(), submission.submittedAt(),
                submission.completedAt(), testResults);
    }
}

