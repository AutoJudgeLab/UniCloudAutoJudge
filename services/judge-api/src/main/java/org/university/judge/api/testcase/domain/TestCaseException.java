package org.university.judge.api.testcase.domain;

public class TestCaseException extends RuntimeException {
    public enum Reason {
        TEST_CASE_NOT_FOUND("Test case not found in the visible current assignment revision"),
        INVALID_TEST_CASE("Input and expected output are required; points must be positive and execution order nonnegative"),
        DUPLICATE_EXECUTION_ORDER("Execution order is already used in this assignment revision"),
        TEST_CASE_CONFLICT("Test case dependencies changed concurrently; reload the current revision and retry");

        private final String message;
        Reason(String message) { this.message = message; }
    }

    private final Reason reason;
    public TestCaseException(Reason reason) { super(reason.message); this.reason = reason; }
    public Reason getReason() { return reason; }
}
