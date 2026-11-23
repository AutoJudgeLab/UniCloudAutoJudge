package org.university.judge.api.worker;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.university.judge.api.outbox.OutboxEventRepository;
import org.university.judge.api.result.application.EvaluationCompletionService;
import org.university.judge.api.submission.domain.Submission;
import org.university.judge.api.submission.domain.SubmissionStatus;
import org.university.judge.api.submission.persistence.SubmissionRepository;
import org.university.judge.api.submission.persistence.SubmissionRequestStore;
import org.university.judge.api.testcase.persistence.TestCaseRepository;
import org.university.judge.messaging.SubmissionCreatedMessageV1;
import org.university.judge.storage.ObjectStorageService;
import org.university.judge.storage.StorageException;

@Service
@PreAuthorize("hasAuthority('EVALUATION_WRITE')")
public class WorkerTransportService {
    private final SubmissionRepository submissions;
    private final OutboxEventRepository outbox;
    private final TestCaseRepository tests;
    private final ObjectStorageService storage;
    private final EvaluationCompletionService completion;
    private final SubmissionRequestStore clock;
    private final WorkerTransportProperties properties;

    public WorkerTransportService(SubmissionRepository submissions, OutboxEventRepository outbox,
            TestCaseRepository tests, ObjectStorageService storage, EvaluationCompletionService completion,
            SubmissionRequestStore clock, WorkerTransportProperties properties) {
        this.submissions = submissions; this.outbox = outbox; this.tests = tests; this.storage = storage;
        this.completion = completion; this.clock = clock; this.properties = properties;
    }

    /** The lock covers the lease check, sealed snapshot and begin; failed source reads roll back. */
    @Transactional
    public WorkerJob begin(UUID submissionId, UUID attemptId, UUID eventId) {
        var submission = submissions.findByIdForUpdate(submissionId).orElseThrow(() ->
                failure(HttpStatus.NOT_FOUND, "SUBMISSION_NOT_FOUND", "Submission not found"));
        var event = outbox.findById(eventId).orElseThrow(() ->
                failure(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_WORKER_EVENT", "Event does not identify this submission"));
        if (!submissionId.equals(event.getAggregateId())
                || !SubmissionCreatedMessageV1.EVENT_TYPE.equals(event.getEventType())
                || event.getEventVersion() != SubmissionCreatedMessageV1.VERSION
                || !submission.getRevision().getId().toString().equals(event.getPayload().get("assignmentRevisionId"))) {
            throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_WORKER_EVENT", "Event does not identify this submission");
        }
        if (submission.getStatus() != SubmissionStatus.PENDING && submission.getStatus() != SubmissionStatus.RUNNING) {
            return null;
        }
        if (submission.getStatus() == SubmissionStatus.RUNNING && !attemptId.equals(submission.getEvaluationAttemptId())
                && submission.getEvaluationLeaseUntil() != null
                && submission.getEvaluationLeaseUntil().isAfter(clock.now())) {
            throw failure(HttpStatus.CONFLICT, "EVALUATION_BUSY", "Another evaluation attempt still holds the lease");
        }
        if (submission.getRevision().getSealedAt() == null) {
            throw failure(HttpStatus.SERVICE_UNAVAILABLE, "INVALID_GRADING_REVISION", "A sealed grading revision is required");
        }
        var configured = tests.findByRevisionIdOrderByExecutionOrder(submission.getRevision().getId()).stream()
                .map(test -> new WorkerJob.Test(test.getId(), test.getInputData(), test.getExpectedOutput(),
                        test.getPoints(), test.getExecutionOrder())).toList();
        if (configured.isEmpty()) {
            throw failure(HttpStatus.SERVICE_UNAVAILABLE, "INVALID_GRADING_REVISION", "Grading tests are unavailable");
        }
        String source = source(submission);
        completion.begin(submissionId, attemptId, submission.getVersion());
        submission.renewEvaluationLease(clock.now().plus(properties.attemptLease()));
        return new WorkerJob(submissionId, attemptId, submission.getRevision().getId(), submission.getProgrammingLanguage(),
                submission.getOriginalFilename(), source, null, null, configured);
    }

    /** Same row lock as begin/complete: an old attempt can never renew its replacement. */
    @Transactional
    public long heartbeat(UUID submissionId, UUID attemptId) {
        var submission = submissions.findByIdForUpdate(submissionId).orElseThrow(() ->
                failure(HttpStatus.NOT_FOUND, "SUBMISSION_NOT_FOUND", "Submission not found"));
        if (submission.getStatus() != SubmissionStatus.RUNNING
                || !attemptId.equals(submission.getEvaluationAttemptId())) {
            throw failure(HttpStatus.CONFLICT, "STALE_ATTEMPT", "Evaluation attempt is no longer current");
        }
        submission.renewEvaluationLease(clock.now().plus(properties.attemptLease()));
        return properties.attemptLease().toMillis();
    }

    private String source(Submission submission) {
        Long size = submission.getSourceSize();
        if (size == null || size < 1 || size > 64L * 1024 * 1024 || submission.getSourceSha256() == null) {
            throw sourceUnavailable();
        }
        try (var object = storage.download(submission.getSourceFileKey())) {
            byte[] bytes = object.content().readNBytes(Math.toIntExact(size + 1));
            if (bytes.length != size || !submission.getSourceSha256().equals(
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))) {
                throw sourceUnavailable();
            }
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (IOException | StorageException unavailable) {
            // A missing/corrupt source is recoverable infrastructure trouble, not an unknown submission.
            throw sourceUnavailable();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    private static WorkerTransportException sourceUnavailable() {
        return failure(HttpStatus.SERVICE_UNAVAILABLE, "SOURCE_UNAVAILABLE", "Submission source is temporarily unavailable");
    }

    private static WorkerTransportException failure(HttpStatus status, String code, String message) {
        return new WorkerTransportException(status, code, message);
    }
}
