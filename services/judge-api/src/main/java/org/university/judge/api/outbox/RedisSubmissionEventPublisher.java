package org.university.judge.api.outbox;

import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.university.judge.messaging.SubmissionCreatedMessageV1;
import org.university.judge.messaging.SubmissionEventPublisher;

@Component
public class RedisSubmissionEventPublisher implements SubmissionEventPublisher {
    private final StringRedisTemplate redis;
    private final OutboxProperties properties;
    public RedisSubmissionEventPublisher(StringRedisTemplate redis, OutboxProperties properties) { this.redis = redis; this.properties = properties; }
    @Override public String publish(SubmissionCreatedMessageV1 event) {
        var id = redis.opsForStream().add(properties.stream(), event.toFields(), XAddOptions.maxlen(properties.maxLength()).approximateTrimming(false));
        if (id == null) throw new IllegalStateException("Redis did not acknowledge XADD");
        return id.getValue();
    }
}

