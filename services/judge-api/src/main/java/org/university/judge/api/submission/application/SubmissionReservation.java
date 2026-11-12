package org.university.judge.api.submission.application;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.university.judge.api.submission.api.SubmissionCreatedResponse;
import org.university.judge.api.submission.domain.SubmissionException;
import org.university.judge.api.submission.persistence.SubmissionRepository;
import org.university.judge.api.submission.persistence.SubmissionRequestStore;
import org.university.judge.storage.ObjectStorageService;
import org.university.judge.storage.PreparedSource;

import static org.university.judge.api.submission.domain.SubmissionException.Code.*;

@Service
public class SubmissionReservation {
    public record Attempt(UUID requestId, UUID revisionId, String objectKey, SubmissionCreatedResponse replay) { }
    private final SubmissionRequestStore requests;
    private final SubmissionAccess access;
    private final SubmissionRepository submissions;
    private final ObjectStorageService storage;
    private final CleanupProperties cleanup;
    public SubmissionReservation(SubmissionRequestStore requests, SubmissionAccess access, SubmissionRepository submissions,
                                 ObjectStorageService storage, CleanupProperties cleanup) {
        this.requests = requests; this.access = access; this.submissions = submissions; this.storage = storage; this.cleanup = cleanup;
    }

    /** Commits the cleanup intent before any remote PUT is attempted. */
    @Transactional
    public Attempt reserve(UUID assignmentId, String key, String fingerprint, PreparedSource source) {
        var student = access.actor(false, true);
        var request = requests.reserve(student.getId(), assignmentId, key, fingerprint);
        access.actor(true, true);
        if (!request.fingerprint().equals(fingerprint)) throw new SubmissionException(IDEMPOTENCY_CONFLICT);
        if (request.acceptedId() != null) {
            return new Attempt(request.id(), null, null, SubmissionCreatedResponse.from(submissions.findById(request.acceptedId()).orElseThrow()));
        }
        var assignment = access.assignment(assignmentId, true);
        var revision = access.grading(assignment);
        if (!requests.now().isBefore(revision.getDeadline())) throw new SubmissionException(DEADLINE_PASSED);
        String objectKey = storage.newSourceKey(request.id(), source);
        requests.registerUpload(request.id(), objectKey, cleanup.initialDelay().getSeconds());
        return new Attempt(request.id(), revision.getId(), objectKey, null);
    }
}
