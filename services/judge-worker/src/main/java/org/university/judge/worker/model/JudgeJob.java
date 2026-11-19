package org.university.judge.worker.model;

import java.util.List;
import java.util.UUID;

/**
 * Everything needed to evaluate one submission, as returned by the API when an attempt begins.
 * timeLimitMs and memoryLimitMb are optional: assignments carry no limits today, so the worker
 * falls back to its configured defaults when they are null.
 */
public record JudgeJob(
        UUID submissionId,
        UUID attemptId,
        UUID assignmentRevisionId,
        String programmingLanguage,
        String originalFilename,
        String source,
        Long timeLimitMs,
        Integer memoryLimitMb,
        List<TestCase> tests) {

    public JudgeJob {
        tests = tests == null ? List.of() : List.copyOf(tests);
    }

    /** Never print student source in logs or diagnostics. */
    @Override public String toString() {
        return "JudgeJob[submissionId=" + submissionId + ", attemptId=" + attemptId + ", language=" + programmingLanguage
                + ", tests=" + tests.size() + "]";
    }
}
