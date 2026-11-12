package org.university.judge.api.assignment.domain;

public class AssignmentException extends RuntimeException {
    public enum Reason {
        ASSIGNMENT_NOT_FOUND("Assignment not found"),
        TEST_CASES_REQUIRED("At least one test case is required before publishing"),
        INVALID_TEST_CASES("Every test case must be valid and have positive points"),
        TEST_CASE_SCORE_MISMATCH("Test case points must sum exactly to the assignment maximum score"),
        INVALID_ASSIGNMENT("A nonblank title and description, positive score, and future deadline are required"),
        ARCHIVED_ASSIGNMENT("Archived assignments are read-only and retained for history"),
        REVISION_REQUIRED("Editing a published assignment or sealed grading configuration requires createNewRevision=true"),
        ASSIGNMENT_HAS_SUBMISSIONS("Assignments with submissions cannot be deleted; archive the assignment instead"),
        GRADING_HISTORY_RETAINED("Sealed grading revisions cannot be deleted; archive the assignment instead"),
        ASSIGNMENT_IN_USE("Assignment dependencies changed concurrently; retry or archive the assignment");

        private final String message;
        Reason(String message) { this.message = message; }
    }

    private final Reason reason;
    public AssignmentException(Reason reason) {
        super(reason.message);
        this.reason = reason;
    }
    public Reason getReason() { return reason; }
}
