package org.university.judge.api.submission.application;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.university.judge.storage.ObjectStorageService;
import org.university.judge.storage.StorageException;

@Service
public class SourceCleanupTransaction {
    private final JdbcTemplate jdbc;
    private final ObjectStorageService storage;
    private final CleanupProperties properties;
    public SourceCleanupTransaction(JdbcTemplate jdbc, ObjectStorageService storage, CleanupProperties properties) {
        this.jdbc = jdbc; this.storage = storage; this.properties = properties;
    }

    @Transactional
    public void reconcile(String key) {
        var tasks = jdbc.query("SELECT request_id FROM source_upload_cleanup WHERE object_key = ? FOR UPDATE SKIP LOCKED",
                (row, index) -> row.getObject(1, UUID.class), key);
        if (tasks.isEmpty()) return;
        UUID requestId = tasks.getFirst();
        var locked = jdbc.query("SELECT id FROM submission_requests WHERE id = ? FOR UPDATE SKIP LOCKED",
                (row, index) -> row.getObject(1, UUID.class), requestId);
        if (locked.isEmpty()) return;
        var acceptedKeys = jdbc.queryForList("SELECT source_file_key FROM submissions WHERE id = ?", String.class, requestId);
        if (acceptedKeys.contains(key)) {
            jdbc.update("DELETE FROM source_upload_cleanup WHERE object_key = ?", key);
            return;
        }
        try { storage.delete(key); }
        catch (StorageException ignored) { /* The durable tombstone remains retryable. */ }
        // Repeat even after a missing-object delete: a PUT may finish late after
        // its DB connection was lost. Never abandon that unaccepted object key.
        jdbc.update("UPDATE source_upload_cleanup SET attempt_count = attempt_count + 1, next_attempt_at = clock_timestamp() + (? * interval '1 second') WHERE object_key = ?",
                properties.retryDelay().getSeconds(), key);
    }
}
