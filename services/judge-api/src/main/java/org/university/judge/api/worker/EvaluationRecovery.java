package org.university.judge.api.worker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL outbox is the recovery source when Redis has lost an unfinished delivery. */
@Service
@ConditionalOnProperty(name = "worker.transport.enabled", havingValue = "true")
public class EvaluationRecovery {
    private final JdbcTemplate jdbc;
    private final java.time.Duration minimumAge;

    public EvaluationRecovery(JdbcTemplate jdbc,
            @org.springframework.beans.factory.annotation.Value("${worker.recovery.minimum-age:5m}") java.time.Duration minimumAge) {
        if (minimumAge.isNegative()) throw new IllegalArgumentException("Recovery minimum age must not be negative");
        this.jdbc = jdbc;
        this.minimumAge = minimumAge;
    }

    @Scheduled(fixedDelayString = "${worker.recovery.interval:PT1M}", initialDelayString = "${worker.recovery.interval:PT1M}")
    @Transactional
    public void recover() {
        // Locks fence concurrent heartbeats/completions and publishers. No business state is reset:
        // duplicate delivery is resolved by begin(), even if an old worker finishes meanwhile.
        jdbc.update("""
                WITH candidates AS (
                    SELECT e.id FROM submissions s JOIN outbox_events e ON e.aggregate_id = s.id
                    WHERE e.event_type = 'SUBMISSION_CREATED' AND e.event_version = 1
                      AND e.published_at < clock_timestamp() - (? * INTERVAL '1 millisecond')
                      AND (s.status = 'PENDING' OR (s.status = 'RUNNING'
                          AND (s.evaluation_lease_until IS NULL OR s.evaluation_lease_until <= clock_timestamp())))
                    ORDER BY e.published_at, e.id LIMIT 100 FOR UPDATE OF s, e SKIP LOCKED
                )
                UPDATE outbox_events e SET published_at = NULL, redis_record_id = NULL,
                    next_attempt_at = clock_timestamp(), last_error_code = NULL
                FROM candidates c WHERE e.id = c.id
                """, minimumAge.toMillis());
    }
}
