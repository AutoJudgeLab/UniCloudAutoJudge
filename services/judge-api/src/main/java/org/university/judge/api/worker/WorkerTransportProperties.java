package org.university.judge.api.worker;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("worker.transport")
public record WorkerTransportProperties(boolean enabled, String token, Duration attemptLease) {
    public WorkerTransportProperties {
        if (enabled && (token == null || !token.matches("[A-Za-z0-9_-]{43,128}"))) {
            throw new IllegalArgumentException("Enabled worker transport requires a random 43–128 character JUDGE_WORKER_TOKEN");
        }
        if (attemptLease == null || attemptLease.compareTo(Duration.ofSeconds(1)) < 0
                || attemptLease.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("Worker attempt lease must be between 1 second and 24 hours");
        }
    }

    @Override public String toString() {
        return "WorkerTransportProperties[enabled=" + enabled + ", token=<redacted>, attemptLease=" + attemptLease + "]";
    }
}
