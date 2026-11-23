package org.university.judge.api.submission.domain;

public class SubmissionException extends RuntimeException {
    public enum Code {
        INVALID_SUBMISSION_REQUEST("Exactly one file and programmingLanguage part and a valid Idempotency-Key are required"),
        UNSUPPORTED_LANGUAGE("Programming language is not supported"),
        LANGUAGE_FILE_MISMATCH("File extension does not match programmingLanguage"),
        IDEMPOTENCY_CONFLICT("This idempotency key is already bound to a different request"),
        ASSIGNMENT_NOT_PUBLISHED("Assignment is not published"),
        DEADLINE_PASSED("The assignment deadline has passed"),
        GRADING_NOT_READY("The current grading revision is not sealed or does not match the assignment"),
        GRADING_REVISION_CHANGED("Grading changed during upload; retry using the same request and idempotency key"),
        SUBMISSION_NOT_FOUND("Submission not found"),
        SUBMISSION_UNAVAILABLE("Submission persistence is temporarily unavailable; retry with the same idempotency key");
        private final String message;
        Code(String message) { this.message = message; }
    }
    private final Code code;
    public SubmissionException(Code code) { super(code.message); this.code = code; }
    public Code getCode() { return code; }
}
