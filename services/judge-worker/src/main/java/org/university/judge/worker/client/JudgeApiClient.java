package org.university.judge.worker.client;

import java.util.UUID;
import org.university.judge.worker.model.EvaluationReport;
import org.university.judge.worker.model.JudgeJob;

/**
 * The worker's only door into the platform. It never talks to PostgreSQL or MinIO itself: the API hands
 * out the source and the sealed tests of one submission and stores the result through
 * EvaluationCompletionService, under a dedicated evaluator identity.
 */
public interface JudgeApiClient {

    /**
     * Starts an evaluation attempt.
     *
     * @throws PermanentApiException the API will never accept this request (unknown submission...)
     * @throws ApiException          transient failure; the delivery is retried
     */
    BeginResult begin(UUID submissionId, UUID attemptId, UUID eventId);

    /**
     * Stores the final report.
     *
     * @throws StaleAttemptException another attempt replaced this one; its result is dropped
     * @throws PermanentApiException the API rejected the report itself
     * @throws ApiException          transient failure
     */
    void complete(EvaluationReport report);

    /** Renews the current attempt and returns the server's lease duration in milliseconds. */
    long heartbeat(UUID submissionId, UUID attemptId);

    record BeginResult(Status status, JudgeJob job) {
        public enum Status {
            STARTED,
            /** The submission already has a final result: a duplicate delivery. */
            ALREADY_COMPLETED,
            /** Another attempt is running and its lease has not expired. */
            BUSY
        }

        public static BeginResult started(JudgeJob job) { return new BeginResult(Status.STARTED, job); }
        public static BeginResult alreadyCompleted() { return new BeginResult(Status.ALREADY_COMPLETED, null); }
        public static BeginResult busy() { return new BeginResult(Status.BUSY, null); }
    }
}
