package org.university.judge.api.observability;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.university.judge.storage.ObjectStorageService;
@Component("s3HealthIndicator")
@ConditionalOnEnabledHealthIndicator("s3")
public class S3HealthIndicator implements HealthIndicator {
    private final ObjectStorageService storage;
    private final String key = "submissions/" + UUID.randomUUID() + "/source/" + UUID.randomUUID() + ".java";
    public S3HealthIndicator(ObjectStorageService storage) { this.storage = storage; }
    @Override public Health health() {
        // HEAD of a missing key verifies authenticated bucket connectivity without creating data.
        try { storage.exists(key); return Health.up().build(); }
        catch (RuntimeException unavailable) { return Health.down().build(); }
    }
}

