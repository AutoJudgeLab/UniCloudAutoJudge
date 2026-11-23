package org.university.judge.api.outbox;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.*;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OutboxProperties.class)
@EnableScheduling
public class OutboxConfiguration {
    @Bean @ConditionalOnProperty(name = "outbox.enabled", havingValue = "true", matchIfMissing = true)
    PublisherJob publisherJob(OutboxPublisher publisher) { return new PublisherJob(publisher); }
    static class PublisherJob {
        private final OutboxPublisher publisher;
        PublisherJob(OutboxPublisher publisher) { this.publisher = publisher; }
        @Scheduled(fixedDelayString = "${outbox.poll-interval:PT1S}")
        public void run() { publisher.publishBatch(); }
    }
}

