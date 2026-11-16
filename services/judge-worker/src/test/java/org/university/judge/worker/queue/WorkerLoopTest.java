package org.university.judge.worker.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.university.judge.messaging.SubmissionCreatedMessageV1;
import org.university.judge.worker.Fixtures;
import org.university.judge.worker.client.JudgeApiClient;
import org.university.judge.worker.config.WorkerProperties;
import org.university.judge.worker.grading.Grader;
import org.university.judge.worker.grading.LanguageProfiles;
import org.university.judge.worker.model.EvaluationReport;
import org.university.judge.worker.model.JudgeJob;
import org.university.judge.worker.processing.SubmissionProcessor;
import org.university.judge.worker.sandbox.RunResult;
import org.university.judge.worker.sandbox.RunResult.Outcome;

/** The whole worker without Redis, HTTP or Docker: a burst of records goes in, every one is settled exactly once. */
class WorkerLoopTest {

    private static final class MemoryQueue implements SubmissionQueue {
        final LinkedBlockingQueue<QueuedEvent> ready = new LinkedBlockingQueue<>();
        final List<String> acknowledged = new ArrayList<>();
        final List<String> dead = new ArrayList<>();
        final AtomicInteger largestBatch = new AtomicInteger();
        final CountDownLatch settled;

        MemoryQueue(int expected) {
            settled = new CountDownLatch(expected);
        }

        @Override public List<QueuedEvent> poll(int max, Duration block) {
            List<QueuedEvent> batch = new ArrayList<>();
            try {
                QueuedEvent first = ready.poll(block.toMillis(), TimeUnit.MILLISECONDS);
                if (first != null) {
                    batch.add(first);
                    ready.drainTo(batch, max - 1);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            largestBatch.accumulateAndGet(batch.size(), Math::max);
            return batch;
        }

        @Override public List<QueuedEvent> reclaimStale(int max, Set<String> inFlight) {
            return List.of();
        }

        @Override public synchronized void acknowledge(QueuedEvent event) {
            acknowledged.add(event.recordId());
            settled.countDown();
        }

        @Override public synchronized void deadLetter(QueuedEvent event, String reason) {
            dead.add(event.recordId() + ":" + reason);
            settled.countDown();
        }
    }

    @Test
    void burstIsDrainedWithBoundedConcurrencyAndEveryRecordSettledOnce() throws Exception {
        int submissions = 40;
        WorkerProperties properties = Fixtures.properties();
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        List<EvaluationReport> stored = new ArrayList<>();

        Grader grader = new Grader(request -> {
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            running.decrementAndGet();
            return new RunResult(Outcome.COMPLETED, 0, "5\n", "", 5);
        }, new LanguageProfiles(properties), properties);
        JudgeApiClient api = new JudgeApiClient() {
            @Override public BeginResult begin(UUID submissionId, UUID attemptId, UUID eventId) {
                return BeginResult.started(new JudgeJob(submissionId, attemptId, UUID.randomUUID(), "JAVA", "Main.java",
                        "public class Main { }", null, null, List.of(Fixtures.test(0, "2 3\n", "5\n", "100"))));
            }
            @Override public void complete(EvaluationReport report) {
                synchronized (stored) {
                    stored.add(report);
                }
            }
        };

        MemoryQueue queue = new MemoryQueue(submissions + 1);
        for (int i = 0; i < submissions; i++) {
            queue.ready.add(new QueuedEvent(i + "-0", new SubmissionCreatedMessageV1(UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), UUID.randomUUID(), Instant.now()).toFields(), 1));
        }
        queue.ready.add(new QueuedEvent("bad-0", java.util.Map.of("eventType", "SOMETHING_ELSE"), 1));

        WorkerLoop loop = new WorkerLoop(queue, new SubmissionProcessor(api, grader, properties), properties,
                new LanguageProfiles(properties));
        loop.start();
        try {
            assertTrue(queue.settled.await(20, TimeUnit.SECONDS), "every record must be settled");
        } finally {
            loop.stop();
        }

        assertEquals(submissions, queue.acknowledged.size());
        assertEquals(submissions, Set.copyOf(queue.acknowledged).size());
        assertEquals(List.of("bad-0:UNSUPPORTED_EVENT"), queue.dead);
        assertEquals(submissions, stored.size());
        assertEquals(submissions, stored.stream().map(EvaluationReport::submissionId).distinct().count());
        assertTrue(peak.get() <= properties.concurrency(), "never more evaluations at once than slots");
        assertTrue(queue.largestBatch.get() <= properties.concurrency(), "never takes more records than free slots");
    }
}
