package org.university.judge.api.submission.application;

import java.time.Duration;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("submission.cleanup")
public record CleanupProperties(@DefaultValue("15m") Duration initialDelay, @DefaultValue("1h") Duration retryDelay,
                                @DefaultValue("20") @Min(1) @Max(100) int batchSize) {
    @AssertTrue(message = "Cleanup delays must be between one second and seven days")
    public boolean isDelayValid() {
        return initialDelay != null && retryDelay != null && initialDelay.getSeconds() >= 1 && retryDelay.getSeconds() >= 1
                && initialDelay.compareTo(Duration.ofDays(7)) <= 0 && retryDelay.compareTo(Duration.ofDays(7)) <= 0;
    }
}
