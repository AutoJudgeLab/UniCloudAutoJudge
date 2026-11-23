package org.university.judge.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Wire contract: identifiers only. Redis record IDs are transport IDs, not deduplication IDs. */
public record SubmissionCreatedMessageV1(UUID eventId, UUID submissionId, UUID assignmentId,
        UUID assignmentRevisionId, Instant createdAt) {
    public static final String EVENT_TYPE = "SUBMISSION_CREATED";
    public static final int VERSION = 1;
    public SubmissionCreatedMessageV1 {
        Objects.requireNonNull(eventId); Objects.requireNonNull(submissionId); Objects.requireNonNull(assignmentId);
        Objects.requireNonNull(assignmentRevisionId); Objects.requireNonNull(createdAt);
    }
    public Map<String, String> toFields() {
        return Map.of("eventId", eventId.toString(), "eventType", EVENT_TYPE, "eventVersion", Integer.toString(VERSION),
                "submissionId", submissionId.toString(), "assignmentId", assignmentId.toString(),
                "assignmentRevisionId", assignmentRevisionId.toString(), "createdAt", createdAt.toString());
    }
}

