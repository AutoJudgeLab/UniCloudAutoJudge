package org.university.judge.api.submission.api;

import java.time.Instant;
import java.util.UUID;
import org.university.judge.api.submission.domain.Submission;
import org.university.judge.api.submission.domain.SubmissionStatus;

public record SubmissionResponse(UUID submissionId, UUID assignmentId, UUID assignmentRevisionId, UUID studentId,
        String programmingLanguage, String originalFilename, Long sourceSize, SubmissionStatus status,
        Instant submittedAt, Instant startedAt, Instant completedAt, int attemptCount) {
    public static SubmissionResponse from(Submission submission) {
        return new SubmissionResponse(submission.getId(), submission.getAssignment().getId(), submission.getRevision().getId(),
                submission.getStudent().getId(), submission.getProgrammingLanguage(), submission.getOriginalFilename(), submission.getSourceSize(),
                submission.getStatus(), submission.getSubmittedAt(), submission.getStartedAt(), submission.getCompletedAt(), submission.getAttemptCount());
    }
}
