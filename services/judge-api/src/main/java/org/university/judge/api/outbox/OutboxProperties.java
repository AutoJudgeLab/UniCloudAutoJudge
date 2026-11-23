package org.university.judge.api.outbox;

import java.time.Duration;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("outbox")
public record OutboxProperties(@DefaultValue("judge:submissions:v1") @Pattern(regexp = "[a-zA-Z0-9:_-]{1,100}") String stream,
        @DefaultValue("100000") @Min(1) @Max(10000000) long maxLength,
        @DefaultValue("25") @Min(1) @Max(100) int batchSize,
        @DefaultValue("1s") Duration retryInitialDelay, @DefaultValue("5m") Duration retryMaxDelay) {
    @AssertTrue(message = "Retry delays must be ordered and between one second and one day")
    public boolean isRetryValid() {
        return retryInitialDelay != null && retryMaxDelay != null && retryInitialDelay.compareTo(Duration.ofSeconds(1)) >= 0
                && retryMaxDelay.compareTo(retryInitialDelay) >= 0 && retryMaxDelay.compareTo(Duration.ofDays(1)) <= 0;
    }
    public long retrySeconds(int previousAttempts) {
        long multiplier = 1L << Math.min(20, previousAttempts);
        return Math.min(retryMaxDelay.toSeconds(), retryInitialDelay.toSeconds() * multiplier);
    }
}

