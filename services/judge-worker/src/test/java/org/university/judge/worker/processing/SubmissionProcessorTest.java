package org.university.judge.worker.processing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.university.judge.messaging.SubmissionCreatedMessageV1;
import org.university.judge.worker.ApiReportRules;
import org.university.judge.worker.Fixtures;
import org.university.judge.worker.client.ApiException;
import org.university.judge.worker.client.JudgeApiClient;
import org.university.judge.worker.client.PermanentApiException;
import org.university.judge.worker.client.StaleAttemptException;
import org.university.judge.worker.config.WorkerProperties;
import org.university.judge.worker.grading.Grader;
import org.university.judge.worker.grading.LanguageProfiles;
import org.university.judge.worker.grading.Verdict;
import org.university.judge.worker.model.EvaluationReport;
import org.university.judge.worker.model.JudgeJob;
import org.university.judge.worker.processing.SubmissionProcessor.Disposition.Action;
import org.university.judge.worker.queue.QueuedEvent;
import org.university.judge.worker.sandbox.RunRequest;
import org.university.judge.worker.sandbox.RunResult;
import org.university.judge.worker.sandbox.RunResult.Outcome;

/** Retry, deduplication and dead-letter decisions, with a scripted API and sandbox. */
class SubmissionProcessorTest {

    private static final int MAX_DELIVERIES = 3;

    /** Records calls and answers them from scripts that tests replace. */
    private static final class FakeApi implements JudgeApiClient {
        java.util.function.LongSupplier onHeartbeat = () -> 240_000;
        @Override public long heartbeat(UUID submissionId, UUID attemptId) { return onHeartbeat.getAsLong(); }
        final List<UUID> attempts = new ArrayList<>();
        final List<EvaluationReport> stored = new ArrayList<>();
        int completeCalls;
        Function<UUID, BeginResult> onBegin;
        Runnable beforeComplete = () -> { };

        @Override public BeginResult begin(UUID submissionId, UUID attemptId, UUID eventId) {
            attempts.add(attemptId);
            return onBegin.apply(attemptId);
        }

        @Override public void complete(EvaluationReport report) {
            completeCalls++;
            beforeComplete.run();
            stored.add(report);
        }
    }

    private final FakeApi api = new FakeApi();
    private Function<RunRequest, RunResult> sandbox = request -> new RunResult(Outcome.COMPLETED, 0, "5\n", "", 50);
    private JudgeJob job;

    private SubmissionProcessor processor() {
        WorkerProperties properties = Fixtures.properties();
        Grader grader = new Grader(request -> sandbox.apply(request), new LanguageProfiles(properties), properties);
        api.onBegin = api.onBegin != null ? api.onBegin : attemptId -> {
            job = new JudgeJob(UUID.randomUUID(), attemptId, UUID.randomUUID(), "JAVA", "Main.java",
                    "public class Main { public static void main(String[] a) { } }", null, null,
                    List.of(Fixtures.test(0, "2 3\n", "5\n", "100")));
            return JudgeApiClient.BeginResult.started(job);
        };
        return new SubmissionProcessor(api, grader, MAX_DELIVERIES, Duration.ZERO);
    }

    private static QueuedEvent event(long deliveryCount) {
        Map<String, String> fields = new SubmissionCreatedMessageV1(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), Instant.now()).toFields();
        return new QueuedEvent("1-0", fields, deliveryCount);
    }

    @Test
    void evaluatesAndAcknowledgesOnlyAfterTheResultIsStored() {
        assertEquals(Action.ACKNOWLEDGE, processor().process(event(1)).action());
        assertEquals(1, api.stored.size());
        EvaluationReport report = api.stored.get(0);
        assertEquals(Verdict.ACCEPTED, report.verdict());
        assertEquals(api.attempts.get(0), report.attemptId());
        assertNull(ApiReportRules.violation(report, job.tests()));
    }

    @Test
    void duplicateDeliveryOfACompletedSubmissionIsAcknowledgedWithoutGrading() {
        api.onBegin = attemptId -> JudgeApiClient.BeginResult.alreadyCompleted();
        sandbox = request -> { throw new AssertionError("must not run code"); };
        assertEquals(Action.ACKNOWLEDGE, processor().process(event(1)).action());
        assertTrue(api.stored.isEmpty());
    }

    @Test
    void submissionRunningElsewhereIsLeftPending() {
        api.onBegin = attemptId -> JudgeApiClient.BeginResult.busy();
        assertEquals(Action.RETRY_LATER, processor().process(event(1)).action());
    }

    @Test
    void unreachableApiLeavesTheRecordPending() {
        api.onBegin = attemptId -> { throw new ApiException("connection refused"); };
        assertEquals(Action.RETRY_LATER, processor().process(event(1)).action());
    }

    @Test
    void everyTryUsesAFreshAttemptId() {
        SubmissionProcessor processor = processor();
        processor.process(event(1));
        processor.process(event(2));
        assertNotEquals(api.attempts.get(0), api.attempts.get(1));
    }

    @Test
    void sandboxOutageIsRetriedAndOnlyTheLastDeliveryReportsInternalError() {
        sandbox = request -> RunResult.sandboxError("Cannot connect to the Docker daemon");
        SubmissionProcessor processor = processor();
        assertEquals(Action.RETRY_LATER, processor.process(event(1)).action());
        assertEquals(Action.RETRY_LATER, processor.process(event(MAX_DELIVERIES - 1)).action());
        assertTrue(api.stored.isEmpty());

        assertEquals(Action.ACKNOWLEDGE, processor.process(event(MAX_DELIVERIES)).action());
        assertEquals(Verdict.INTERNAL_ERROR, api.stored.get(0).verdict());
        assertNull(ApiReportRules.violation(api.stored.get(0), job.tests()));
    }

    @Test
    void lostResultUploadIsRetriedInPlaceWithTheSameAttempt() {
        int[] failures = {2};
        api.beforeComplete = () -> { if (failures[0]-- > 0) throw new ApiException("timeout"); };
        assertEquals(Action.ACKNOWLEDGE, processor().process(event(1)).action());
        assertEquals(3, api.completeCalls);
        assertEquals(1, api.attempts.size());
    }

    @Test
    void resultThatCannotBeStoredLeavesTheRecordPending() {
        api.beforeComplete = () -> { throw new ApiException("503"); };
        assertEquals(Action.RETRY_LATER, processor().process(event(1)).action());
        assertEquals(3, api.completeCalls);
    }

    @Test
    void replacedAttemptCannotAcknowledgeWorkOwnedByItsReplacement() {
        api.beforeComplete = () -> { throw new StaleAttemptException("replaced"); };
        assertEquals(Action.RETRY_LATER, processor().process(event(1)).action());
        assertTrue(api.stored.isEmpty());
    }

    @Test
    void rejectionsThatRetryingCannotFixGoToTheDeadLetterStream() {
        api.beforeComplete = () -> { throw new PermanentApiException("422"); };
        SubmissionProcessor.Disposition rejected = processor().process(event(1));
        assertEquals(Action.DEAD_LETTER, rejected.action());
        assertEquals("RESULT_REJECTED", rejected.reason());

        api.onBegin = attemptId -> { throw new PermanentApiException("404"); };
        assertEquals("BEGIN_REJECTED", processor().process(event(1)).reason());
    }

    @Test
    void malformedOrUnknownEventsAreParkedNotDropped() {
        Map<String, String> broken = new HashMap<>(event(1).fields());
        broken.put("submissionId", "not-a-uuid");
        assertEquals("INVALID_EVENT", processor().process(new QueuedEvent("1-0", broken, 1)).reason());

        Map<String, String> future = new HashMap<>(event(1).fields());
        future.put("eventVersion", "2");
        assertEquals("UNSUPPORTED_EVENT", processor().process(new QueuedEvent("1-0", future, 1)).reason());
        assertTrue(api.attempts.isEmpty());
    }

    @Test
    void rejectedReportCanFinalizeTheCurrentAttemptAsInternalError() {
        int[] remaining = {1};
        api.beforeComplete = () -> { if (remaining[0]-- > 0) throw new PermanentApiException("422"); };
        assertEquals(Action.ACKNOWLEDGE, processor().process(event(1)).action());
        assertEquals(Verdict.INTERNAL_ERROR, api.stored.getFirst().verdict());
        assertEquals(2, api.completeCalls);
    }

    @Test
    void staleHeartbeatStopsGradingWithoutReportingAnInternalErrorOrAcknowledging() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        api.onHeartbeat = () -> {
            if (calls.incrementAndGet() > 1) throw new StaleAttemptException("replaced");
            return 90;
        };
        sandbox = request -> {
            try { Thread.sleep(5000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return RunResult.sandboxError("interrupted");
        };
        try {
            assertEquals(Action.RETRY_LATER, processor().process(event(MAX_DELIVERIES)).action());
            assertTrue(api.stored.isEmpty());
            assertEquals(0, api.completeCalls);
        } finally { Thread.interrupted(); }
    }
}
