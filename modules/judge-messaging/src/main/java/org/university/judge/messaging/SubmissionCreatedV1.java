package org.university.judge.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Internal durable payload. The public Redis wire contract is SubmissionCreatedMessageV1. */
public record SubmissionCreatedV1(UUID submissionId, UUID assignmentId, UUID assignmentRevisionId, UUID studentId,
        String sourceFileKey, String programmingLanguage, Instant submittedAt, String sourceSha256, long sourceSize) {
    public static final String EVENT_TYPE = "SUBMISSION_CREATED";
    public static final int VERSION = 1;
    public Map<String, Object> toPayload() {
        return Map.of("submissionId", submissionId.toString(), "assignmentId", assignmentId.toString(),
                "assignmentRevisionId", assignmentRevisionId.toString(), "studentId", studentId.toString(),
                "sourceFileKey", sourceFileKey, "programmingLanguage", programmingLanguage, "submittedAt", submittedAt.toString(),
                "sourceSha256", sourceSha256, "sourceSize", sourceSize);
    }
}
