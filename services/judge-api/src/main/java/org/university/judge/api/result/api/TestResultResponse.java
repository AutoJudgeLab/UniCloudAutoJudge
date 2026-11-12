package org.university.judge.api.result.api;

import java.math.BigDecimal;
import java.util.UUID;
import org.university.judge.api.result.TestCaseResult;
import org.university.judge.api.result.Verdict;

public record TestResultResponse(UUID testCaseId, Verdict status, BigDecimal awardedPoints, Long executionTimeMs,
        String inputData, String expectedOutput, String actualOutput) {
    public static TestResultResponse from(TestCaseResult result, boolean privileged) {
        var test = result.getTestCase();
        boolean visible = privileged || !test.isHidden();
        return new TestResultResponse(test.getId(), result.getStatus(), result.getAwardedPoints(), result.getExecutionTimeMs(),
                visible ? test.getInputData() : null, visible ? test.getExpectedOutput() : null,
                visible ? result.getActualOutput() : null);
    }
}

