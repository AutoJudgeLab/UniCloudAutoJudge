package org.university.judge.api.submission.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC keeps durable upload intents independent of entity flush/merge behavior. */
@Repository
public class SubmissionRequestStore {
    private final JdbcTemplate jdbc;
    public SubmissionRequestStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Request(UUID id, UUID studentId, UUID assignmentId, String fingerprint, UUID acceptedId) { }

    public Request reserve(UUID student, UUID assignment, String key, String fingerprint) {
        jdbc.update("""
                INSERT INTO submission_requests(id, student_id, assignment_id, idempotency_key, fingerprint)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT (student_id, idempotency_key) DO NOTHING
                """, UUID.randomUUID(), student, assignment, key, fingerprint);
        return jdbc.queryForObject("SELECT * FROM submission_requests WHERE student_id = ? AND idempotency_key = ? FOR UPDATE",
                this::map, student, key);
    }

    public Request lock(UUID id) {
        return jdbc.queryForObject("SELECT * FROM submission_requests WHERE id = ? FOR UPDATE", this::map, id);
    }

    public void registerUpload(UUID requestId, String key, long graceSeconds) {
        jdbc.update("INSERT INTO source_upload_cleanup(object_key, request_id, next_attempt_at) VALUES (?, ?, clock_timestamp() + (? * interval '1 second'))",
                key, requestId, graceSeconds);
    }

    public void accepted(UUID requestId) {
        jdbc.update("UPDATE submission_requests SET accepted_submission_id = id WHERE id = ?", requestId);
    }

    public void forgetCleanup(String key) { jdbc.update("DELETE FROM source_upload_cleanup WHERE object_key = ?", key); }

    public Instant now() { return jdbc.queryForObject("SELECT clock_timestamp()", java.time.OffsetDateTime.class).toInstant(); }

    private Request map(ResultSet row, int index) throws SQLException {
        return new Request(row.getObject("id", UUID.class), row.getObject("student_id", UUID.class),
                row.getObject("assignment_id", UUID.class), row.getString("fingerprint"), row.getObject("accepted_submission_id", UUID.class));
    }
}
