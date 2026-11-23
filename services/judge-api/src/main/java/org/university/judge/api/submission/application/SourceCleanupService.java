package org.university.judge.api.submission.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SourceCleanupService {
    private static final Logger LOG = LoggerFactory.getLogger(SourceCleanupService.class);
    private final JdbcTemplate jdbc;
    private final SourceCleanupTransaction cleanup;
    private final CleanupProperties properties;
    public SourceCleanupService(JdbcTemplate jdbc, SourceCleanupTransaction cleanup, CleanupProperties properties) {
        this.jdbc = jdbc; this.cleanup = cleanup; this.properties = properties;
    }

    public void tryNow(String key) {
        try { cleanup.reconcile(key); }
        catch (RuntimeException ignored) { LOG.warn("Source reconciliation deferred; the durable cleanup intent will be retried"); }
    }

    public void reconcileBatch() {
        try {
            var keys = jdbc.queryForList("SELECT object_key FROM source_upload_cleanup WHERE next_attempt_at <= clock_timestamp() ORDER BY next_attempt_at, object_key LIMIT ?",
                    String.class, properties.batchSize());
            keys.forEach(this::tryNow);
        } catch (RuntimeException ignored) { LOG.warn("Source reconciliation could not access its queue; it will retry"); }
    }
}
