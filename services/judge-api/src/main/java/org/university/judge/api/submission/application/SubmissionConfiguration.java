package org.university.judge.api.submission.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CleanupProperties.class)
@EnableScheduling
public class SubmissionConfiguration {
    @Bean
    @ConditionalOnProperty(name = "submission.cleanup.enabled", havingValue = "true", matchIfMissing = true)
    CleanupJob cleanupJob(SourceCleanupService service) { return new CleanupJob(service); }

    static class CleanupJob {
        private final SourceCleanupService service;
        CleanupJob(SourceCleanupService service) { this.service = service; }
        @Scheduled(fixedDelayString = "${submission.cleanup.interval:PT1M}")
        public void run() { service.reconcileBatch(); }
    }
}
