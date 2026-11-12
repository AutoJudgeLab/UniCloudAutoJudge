package org.university.judge.api.outbox;

import java.time.OffsetDateTime;
import java.util.UUID;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.university.judge.messaging.SubmissionCreatedMessageV1;
import org.university.judge.messaging.SubmissionEventPublisher;

@Service
public class OutboxDelivery {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxDelivery.class);
    private final JdbcTemplate jdbc;
    private final SubmissionEventPublisher publisher;
    private final OutboxProperties properties;
    private final MeterRegistry meters;
    public OutboxDelivery(JdbcTemplate jdbc, SubmissionEventPublisher publisher, OutboxProperties properties, MeterRegistry meters) {
        this.jdbc = jdbc; this.publisher = publisher; this.properties = properties; this.meters = meters;
    }

    @Transactional(timeout = 10)
    public boolean deliverNext() {
        var rows = jdbc.queryForList("""
            SELECT id, aggregate_id, event_type, event_version, created_at, attempt_count,
                payload->>'submissionId' AS submission_id, payload->>'assignmentId' AS assignment_id,
                payload->>'assignmentRevisionId' AS revision_id
            FROM outbox_events WHERE published_at IS NULL AND next_attempt_at <= clock_timestamp()
            ORDER BY next_attempt_at, created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
            """);
        if (rows.isEmpty()) return false;
        var row = rows.getFirst();
        UUID id = (UUID) row.get("id");
        int attempts = ((Number) row.get("attempt_count")).intValue();
        SubmissionCreatedMessageV1 message;
        try {
            if (!SubmissionCreatedMessageV1.EVENT_TYPE.equals(row.get("event_type")) || ((Number) row.get("event_version")).intValue() != 1)
                throw new IllegalArgumentException();
            UUID submission = UUID.fromString((String) row.get("submission_id"));
            if (!submission.equals(row.get("aggregate_id"))) throw new IllegalArgumentException();
            message = new SubmissionCreatedMessageV1(id, submission, UUID.fromString((String) row.get("assignment_id")),
                    UUID.fromString((String) row.get("revision_id")), ((java.sql.Timestamp) row.get("created_at")).toInstant());
        } catch (RuntimeException invalid) { retry(id, attempts, "INVALID_EVENT"); return true; }
        String recordId;
        try { recordId = publisher.publish(message); }
        catch (RuntimeException unavailable) { retry(id, attempts, "REDIS_UNAVAILABLE"); return true; }
        // If this write/commit fails after XADD, the unchanged durable row is retried with the same eventId.
        jdbc.update("""
            UPDATE outbox_events SET published_at = clock_timestamp(), redis_record_id = ?, last_error_code = NULL,
                attempt_count = LEAST(attempt_count::bigint + 1, 2147483647)::int WHERE id = ?
            """, recordId, id);
        meters.counter("judge.outbox.publish.attempts", "outcome", "delivered").increment();
        LOG.atInfo().addKeyValue("eventId", id).addKeyValue("outcome", "delivered").log("Outbox delivery acknowledged");
        return true;
    }
    private void retry(UUID id, int attempts, String code) {
        jdbc.update("""
            UPDATE outbox_events SET attempt_count = LEAST(attempt_count::bigint + 1, 2147483647)::int,
                last_error_code = ?, next_attempt_at = clock_timestamp() + (? * interval '1 second') WHERE id = ?
            """, code, properties.retrySeconds(attempts), id);
        meters.counter("judge.outbox.publishing.failures", "reason", code).increment();
        LOG.atWarn().addKeyValue("eventId", id).addKeyValue("reason", code).addKeyValue("attempt", (long) attempts + 1)
                .log("Outbox delivery deferred");
    }
}

