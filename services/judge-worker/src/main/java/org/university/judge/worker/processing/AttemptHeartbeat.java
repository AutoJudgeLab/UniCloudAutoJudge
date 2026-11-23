package org.university.judge.worker.processing;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.LoggerFactory;
import org.university.judge.worker.client.JudgeApiClient;
import org.university.judge.worker.client.StaleAttemptException;
import org.university.judge.worker.model.JudgeJob;

/** Owns renewal for exactly one grading call, including its result upload retries. */
final class AttemptHeartbeat implements AutoCloseable {
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread thread;

    AttemptHeartbeat(JudgeApiClient api, JudgeJob job) {
        long lease = api.heartbeat(job.submissionId(), job.attemptId());
        if (lease < 1) throw new IllegalStateException("Invalid heartbeat lease");
        Thread owner = Thread.currentThread();
        thread = Thread.ofVirtual().name("judge-heartbeat").start(() -> {
            long remainingLease = lease;
            long renewed = System.nanoTime();
            while (!closed.get()) {
                try {
                    Thread.sleep(Math.max(1, remainingLease / 3));
                    if (closed.get()) return;
                    remainingLease = api.heartbeat(job.submissionId(), job.attemptId());
                    if (remainingLease < 1) throw new IllegalStateException("Invalid heartbeat lease");
                    renewed = System.nanoTime();
                } catch (InterruptedException stopped) {
                    return;
                } catch (StaleAttemptException stale) {
                    if (!closed.get()) owner.interrupt();
                    return;
                } catch (RuntimeException unavailable) {
                    LoggerFactory.getLogger(AttemptHeartbeat.class).warn("Attempt {} heartbeat failed: {}",
                            job.attemptId(), unavailable.toString());
                    if ((System.nanoTime() - renewed) / 1_000_000 >= remainingLease) {
                        if (!closed.get()) owner.interrupt();
                        return;
                    }
                }
            }
        });
    }

    @Override public void close() {
        closed.set(true);
        thread.interrupt();
        try { thread.join(2000); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
