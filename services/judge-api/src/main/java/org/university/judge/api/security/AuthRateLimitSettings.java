package org.university.judge.api.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("security.rate-limit")
public record AuthRateLimitSettings(boolean enabled, int loginLimit, int registerLimit, Duration window) {
    public AuthRateLimitSettings {
        if (loginLimit < 1 || registerLimit < 1 || window == null
                || window.compareTo(Duration.ofSeconds(1)) < 0 || window.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("Authentication rate limits must be positive with a 1 second–1 hour window");
        }
    }
}
