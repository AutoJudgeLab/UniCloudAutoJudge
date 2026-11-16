package org.university.judge.worker.processing;

import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.university.judge.messaging.SubmissionCreatedMessageV1;
import org.university.judge.worker.client.ApiException;
import org.university.judge.worker.client.JudgeApiClient;
import org.university.judge.worker.client.JudgeApiClient.BeginResult;
import org.university.judge.worker.client.PermanentApiException;
import org.university.judge.worker.client.StaleAttemptException;
import org.university.judge.worker.config.WorkerProperties;
import org.university.judge.worker.grading.Grader;
import org.university.judge.worker.model.EvaluationReport;
import org.university.judge.worker.model.JudgeJob;
import org.university.judge.worker.queue.QueuedEvent;

/**
 * Turns one delivered event into at most one stored result and tells the loop what to do with the record.
 *
 * <p>Deliveries are at-least-once and may repeat or arrive out of order. Deduplication lives in the API:
 * every try uses a fresh attempt ID, the API admits one attempt at a time and refuses results from a
 * replaced attempt, and a completed submission can never be reopened. The record is acknowledged only
 * after the outcome is durable there.
 */
@Component
public class SubmissionProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(SubmissionProcessor.class);
    private static final int RESULT_UPLOAD_TRIES = 3;

    private final JudgeApiClient api;
    private final Grader grader;
    private final int maxDeliveries;
    private final Duration uploadBackoff;

    @Autowired
    public SubmissionProcessor(JudgeApiClient api, Grader grader, WorkerProperties properties) {
        this(api, grader, properties.maxDeliveries(), Duration.ofSeconds(2));
    }

    SubmissionProcessor(JudgeApiClient api, Grader grader, int maxDeliveries, Duration uploadBackoff) {
        this.api = api;
        this.grader = grader;
        this.maxDeliveries = maxDeliveries;
        this.uploadBackoff = uploadBackoff;
    }

    public Disposition process(QueuedEvent event) {
        UUID eventId;
        UUID submissionId;
        try {
            if (!SubmissionCreatedMessageV1.EVENT_TYPE.equals(event.fields().get("eventType"))
                    || !Integer.toString(SubmissionCreatedMessageV1.VERSION).equals(event.fields().get("eventVersion"))) {
                return Disposition.deadLetter("UNSUPPORTED_EVENT");
            }
            eventId = UUID.fromString(event.fields().get("eventId"));
            submissionId = UUID.fromString(event.fields().get("submissionId"));
        } catch (RuntimeException malformed) {
            return Disposition.deadLetter("INVALID_EVENT");
        }

        BeginResult begin;
        try {
            begin = api.begin(submissionId, UUID.randomUUID(), eventId);
        } catch (PermanentApiException rejected) {
            LOG.error("Submission {} cannot be evaluated: {}", submissionId, rejected.getMessage());
            return Disposition.deadLetter("BEGIN_REJECTED");
        } catch (RuntimeException unavailable) {
            LOG.warn("Could not begin submission {}: {}", submissionId, unavailable.getMessage());
            return Disposition.retryLater();
        }
        switch (begin.status()) {
            case ALREADY_COMPLETED -> {
                LOG.info("Submission {} already has a result; duplicate event {} acknowledged", submissionId, eventId);
                return Disposition.acknowledge();
            }
            case BUSY -> {
                return Disposition.retryLater();
            }
            case STARTED -> { }
        }

        JudgeJob job = begin.job();
        long started = System.nanoTime();
        EvaluationReport report;
        try {
            report = grader.grade(job);
        } catch (RuntimeException failure) {
            if (event.deliveryCount() < maxDeliveries) {
                LOG.warn("Evaluation of submission {} failed on delivery {}: {}", submissionId, event.deliveryCount(),
                        failure.toString());
                return Disposition.retryLater();
            }
            // Out of retries: give the student a final state instead of a submission that runs forever.
            LOG.error("Evaluation of submission {} failed on its last delivery; reporting INTERNAL_ERROR", submissionId, failure);
            report = EvaluationReport.internalError(job);
        }

        Disposition stored = store(report);
        if (stored.action() == Disposition.Action.ACKNOWLEDGE) {
            LOG.info("Submission {} evaluated: verdict={} score={} passed={}/{} gradingMs={}", submissionId,
                    report.verdict(), report.totalScore().toPlainString(), report.passedTests(), report.totalTests(),
                    (System.nanoTime() - started) / 1_000_000);
        }
        return stored;
    }

    /** A lost response is retried in place: the API treats an identical report for the same attempt as a replay. */
    private Disposition store(EvaluationReport report) {
        for (int attempt = 1; ; attempt++) {
            try {
                api.complete(report);
                return Disposition.acknowledge();
            } catch (StaleAttemptException replaced) {
                LOG.info("Attempt {} was replaced; its result is dropped", report.attemptId());
                return Disposition.acknowledge();
            } catch (PermanentApiException rejected) {
                LOG.error("API rejected the result of submission {}: {}", report.submissionId(), rejected.getMessage());
                return Disposition.deadLetter("RESULT_REJECTED");
            } catch (ApiException unavailable) {
                if (attempt >= RESULT_UPLOAD_TRIES) {
                    LOG.warn("Result of submission {} not stored after {} tries", report.submissionId(), attempt);
                    return Disposition.retryLater();
                }
                try {
                    Thread.sleep(uploadBackoff.multipliedBy(attempt));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return Disposition.retryLater();
                }
            }
        }
    }

    public record Disposition(Action action, String reason) {
        public enum Action {
            ACKNOWLEDGE,
            /** Leave the record pending; it is redelivered once it has been idle long enough. */
            RETRY_LATER,
            DEAD_LETTER
        }

        static Disposition acknowledge() { return new Disposition(Action.ACKNOWLEDGE, null); }
        static Disposition retryLater() { return new Disposition(Action.RETRY_LATER, null); }
        static Disposition deadLetter(String reason) { return new Disposition(Action.DEAD_LETTER, reason); }
    }
}
