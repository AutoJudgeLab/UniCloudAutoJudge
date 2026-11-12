package org.university.judge.api.testcase.api;

import java.math.BigDecimal;
import java.util.UUID;
import org.university.judge.api.testcase.domain.TestCase;

public record TestCaseResponse(UUID id, UUID assignmentId, String inputData, String expectedOutput,
                               BigDecimal points, boolean isHidden, int executionOrder) {
    public static TestCaseResponse from(TestCase test) {
        return new TestCaseResponse(test.getId(), test.getAssignment().getId(), test.getInputData(),
                test.getExpectedOutput(), test.getPoints(), test.isHidden(), test.getExecutionOrder());
    }
}
