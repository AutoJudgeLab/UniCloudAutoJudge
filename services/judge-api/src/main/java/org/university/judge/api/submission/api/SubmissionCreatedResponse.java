package org.university.judge.api.submission.api;

import java.time.Instant;
import java.util.UUID;
import org.university.judge.api.submission.domain.Submission;
import org.university.judge.api.submission.domain.SubmissionStatus;

public record SubmissionCreatedResponse(UUID submissionId, UUID assignmentId, SubmissionStatus status, Instant submittedAt) {
    public static SubmissionCreatedResponse from(Submission submission) {
        // Replays return the original acceptance response, even after evaluation changes status.
        return new SubmissionCreatedResponse(submission.getId(), submission.getAssignment().getId(), SubmissionStatus.PENDING, submission.getSubmittedAt());
    }
}
