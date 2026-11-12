package org.university.judge.api.submission.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.university.judge.api.outbox.OutboxEvent;
import org.university.judge.api.outbox.OutboxEventRepository;
import org.university.judge.api.submission.api.SubmissionCreatedResponse;
import org.university.judge.api.submission.domain.ProgrammingLanguage;
import org.university.judge.api.submission.domain.Submission;
import org.university.judge.api.submission.domain.SubmissionException;
import org.university.judge.api.submission.persistence.SubmissionRepository;
import org.university.judge.api.submission.persistence.SubmissionRequestStore;
import org.university.judge.messaging.SubmissionCreatedV1;
import org.university.judge.storage.ObjectStorageService;
import org.university.judge.storage.PreparedSource;

import static org.university.judge.api.submission.domain.SubmissionException.Code.*;

@Service
public class SubmissionAcceptance {
    private final SubmissionRequestStore requests;
    private final SubmissionAccess access;
    private final ObjectStorageService storage;
    private final SubmissionRepository submissions;
    private final OutboxEventRepository events;
    public SubmissionAcceptance(SubmissionRequestStore requests, SubmissionAccess access, ObjectStorageService storage,
                                SubmissionRepository submissions, OutboxEventRepository events) {
        this.requests = requests; this.access = access; this.storage = storage; this.submissions = submissions; this.events = events;
    }

    @Transactional
    public SubmissionCreatedResponse accept(SubmissionReservation.Attempt attempt, ProgrammingLanguage language, PreparedSource source) {
        // Only this idempotency row is held during S3 I/O. No second connection or
        // assignment/user write lock is held across that upload.
        var request = requests.lock(attempt.requestId());
        var actor = access.actor(false, true);
        if (!request.studentId().equals(actor.getId())) throw new org.springframework.security.access.AccessDeniedException("Submission ownership is required");
        if (request.acceptedId() != null) {
            requests.forgetCleanup(attempt.objectKey());
            return SubmissionCreatedResponse.from(submissions.findById(request.acceptedId()).orElseThrow());
        }
        storage.upload(attempt.objectKey(), source);
        actor = access.actor(true, true);
        var assignment = access.assignment(request.assignmentId(), true);
        var revision = access.grading(assignment);
        if (!revision.getId().equals(attempt.revisionId())) throw new SubmissionException(GRADING_REVISION_CHANGED);
        var acceptedAt = requests.now();
        if (!acceptedAt.isBefore(revision.getDeadline())) throw new SubmissionException(DEADLINE_PASSED);

        var submission = submissions.saveAndFlush(new Submission(request.id(), revision, actor, attempt.objectKey(), source.originalFilename(),
                language.name(), acceptedAt, source.size(), source.sha256()));
        var event = new SubmissionCreatedV1(submission.getId(), assignment.getId(), revision.getId(), actor.getId(),
                attempt.objectKey(), language.name(), acceptedAt, source.sha256(), source.size());
        events.saveAndFlush(new OutboxEvent(submission.getId(), SubmissionCreatedV1.EVENT_TYPE, SubmissionCreatedV1.VERSION, event.toPayload()));
        requests.accepted(request.id());
        requests.forgetCleanup(attempt.objectKey());
        // The transactional proxy commits before returning to the HTTP orchestrator.
        return SubmissionCreatedResponse.from(submission);
    }
}
