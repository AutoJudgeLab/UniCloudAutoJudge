package org.university.judge.worker.queue;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/** The delivery buffer as the worker sees it. Redis today; an interface so the loop can be tested without it. */
public interface SubmissionQueue {

    /** Waits up to {@code block} for at most {@code max} records never delivered to this group before. */
    List<QueuedEvent> poll(int max, Duration block);

    /**
     * Takes over deliveries that were never acknowledged, typically because a worker died mid-evaluation.
     * The processor decides whether a grading failure has exhausted its retries.
     *
     * @param inFlight record IDs this process is still working on; they are left alone
     */
    List<QueuedEvent> reclaimStale(int max, Set<String> inFlight);

    /** Call only after the outcome is durable in the API. */
    void acknowledge(QueuedEvent event);

    /** Refresh idle time only while this consumer still owns the pending record. */
    void touch(QueuedEvent event);

    /** Parks a record for an operator and stops redelivering it. Nothing is ever dropped silently. */
    void deadLetter(QueuedEvent event, String reason);
}
