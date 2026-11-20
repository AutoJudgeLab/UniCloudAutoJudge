package org.university.judge.api.worker;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Internal response only; never expose source or sealed tests on the public submission endpoints. */
public record WorkerJob(UUID submissionId, UUID attemptId, UUID assignmentRevisionId,
        String programmingLanguage, String originalFilename, String source, Long timeLimitMs,
        Integer memoryLimitMb, List<Test> tests) {
    public record Test(UUID testCaseId, String inputData, String expectedOutput, BigDecimal points, int executionOrder) {
        @Override public String toString() { return "Test[testCaseId=" + testCaseId + "]"; }
    }
    @Override public String toString() {
        return "WorkerJob[submissionId=" + submissionId + ", attemptId=" + attemptId + "]";
    }
}
