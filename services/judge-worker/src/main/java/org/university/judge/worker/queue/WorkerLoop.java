package org.university.judge.worker.queue;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.university.judge.worker.config.WorkerProperties;
import org.university.judge.worker.grading.LanguageProfiles;
import org.university.judge.worker.processing.SubmissionProcessor;
import org.university.judge.worker.processing.SubmissionProcessor.Disposition;

/**
 * Pulls records and evaluates up to {@code concurrency} of them at once. It never takes more records
 * than it has free slots for, so the backlog of a deadline rush waits in Redis, where other worker
 * instances can share it, instead of in this process's memory.
 */
@Component
public class WorkerLoop implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerLoop.class);
    private static final long RECLAIM_EVERY_MILLIS = 15_000;

    private final SubmissionQueue queue;
    private final SubmissionProcessor processor;
    private final WorkerProperties properties;
    private final LanguageProfiles languages;
    private final Semaphore slots;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    private volatile boolean running;
    private Thread loop;
    private ExecutorService pool;

    public WorkerLoop(SubmissionQueue queue, SubmissionProcessor processor, WorkerProperties properties,
            LanguageProfiles languages) {
        this.queue = queue;
        this.processor = processor;
        this.properties = properties;
        this.languages = languages;
        this.slots = new Semaphore(properties.concurrency());
    }

    @Override
    public void start() {
        pool = Executors.newFixedThreadPool(properties.concurrency(), Thread.ofPlatform().name("judge-eval-", 0).factory());
        running = true;
        loop = Thread.ofPlatform().name("judge-consumer").start(this::run);
        LOG.info("Worker {} consuming {} as group {} with {} slot(s); languages {}", properties.consumerName(),
                properties.stream(), properties.group(), properties.concurrency(), languages.enabledKeys());
    }

    /** Stops taking work and lets running evaluations finish; whatever does not finish stays pending in Redis. */
    @Override
    public void stop() {
        running = false;
        try {
            if (loop != null) loop.join(properties.blockTimeout().toMillis() + 5_000);
            if (pool != null) {
                pool.shutdown();
                if (!pool.awaitTermination(properties.shutdownGrace().toMillis(), TimeUnit.MILLISECONDS)) {
                    LOG.warn("Shutdown grace elapsed with evaluations still running; they will be redelivered");
                    pool.shutdownNow();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void run() {
        long nextReclaim = 0;
        while (running) {
            try {
                int free = slots.availablePermits();
                if (free == 0) {
                    Thread.sleep(100);
                    continue;
                }
                List<QueuedEvent> events = List.of();
                if (System.currentTimeMillis() >= nextReclaim) {
                    nextReclaim = System.currentTimeMillis() + RECLAIM_EVERY_MILLIS;
                    events = queue.reclaimStale(free, inFlight);
                }
                if (events.isEmpty()) events = queue.poll(free, properties.blockTimeout());
                for (QueuedEvent event : events) dispatch(event);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                LOG.error("Consumer loop error: {}", e.toString());
                pause();
            }
        }
    }

    private void dispatch(QueuedEvent event) throws InterruptedException {
        slots.acquire();
        inFlight.add(event.recordId());
        try {
            pool.execute(() -> {
                try {
                    handle(event);
                } finally {
                    inFlight.remove(event.recordId());
                    slots.release();
                }
            });
        } catch (RuntimeException rejected) {
            inFlight.remove(event.recordId());
            slots.release();
            throw rejected;
        }
    }

    void handle(QueuedEvent event) {
        try {
            Disposition disposition = processor.process(event);
            switch (disposition.action()) {
                case ACKNOWLEDGE -> queue.acknowledge(event);
                case DEAD_LETTER -> queue.deadLetter(event, disposition.reason());
                case RETRY_LATER -> { }
            }
        } catch (RuntimeException e) {
            // Unacknowledged on purpose: the record stays pending and is retried.
            LOG.error("Record {} left pending after an unexpected error: {}", event.recordId(), e.toString());
        }
    }

    private void pause() {
        try {
            Thread.sleep(1_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }
}
