package org.university.judge.worker.queue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.university.judge.worker.config.WorkerProperties;

/**
 * Redis Streams consumer-group access. Every worker instance shares one group, so Redis hands each
 * record to exactly one of them; unacknowledged records stay in the group's pending list until
 * somebody acknowledges or reclaims them.
 */
@Component
public class RedisStreamQueue implements SubmissionQueue {

    private static final Logger LOG = LoggerFactory.getLogger(RedisStreamQueue.class);
    private static final long PENDING_SCAN = 100;
    private static final long DEAD_LETTER_MAX_LENGTH = 10_000;

    private final StreamOperations<String, Object, Object> streams;
    private final WorkerProperties properties;
    private volatile boolean groupReady;

    public RedisStreamQueue(StringRedisTemplate redis, WorkerProperties properties) {
        this.streams = redis.opsForStream();
        this.properties = properties;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<QueuedEvent> poll(int max, Duration block) {
        ensureGroup();
        List<MapRecord<String, Object, Object>> records = streams.read(
                Consumer.from(properties.group(), properties.consumerName()),
                StreamReadOptions.empty().count(max).block(block),
                StreamOffset.create(properties.stream(), ReadOffset.lastConsumed()));
        List<QueuedEvent> events = new ArrayList<>();
        if (records != null) {
            for (MapRecord<String, Object, Object> record : records) events.add(event(record, 1));
        }
        return events;
    }

    @Override
    public List<QueuedEvent> reclaimStale(int max, Set<String> inFlight) {
        ensureGroup();
        List<QueuedEvent> events = new ArrayList<>();
        PendingMessages pending = streams.pending(properties.stream(), properties.group(), Range.unbounded(), PENDING_SCAN);
        if (pending == null) return events;
        for (PendingMessage message : pending) {
            if (events.size() >= max) break;
            String id = message.getIdAsString();
            if (inFlight.contains(id) || message.getElapsedTimeSinceLastDelivery().compareTo(properties.reclaimIdle()) < 0) {
                continue;
            }
            // XCLAIM succeeds only if the record is still idle, so two workers cannot both take it.
            List<MapRecord<String, Object, Object>> claimed = streams.claim(properties.stream(), properties.group(),
                    properties.consumerName(), properties.reclaimIdle(), message.getId());
            if (claimed == null || claimed.isEmpty()) continue;
            QueuedEvent event = event(claimed.get(0), message.getTotalDeliveryCount() + 1);
            if (message.getTotalDeliveryCount() >= properties.maxDeliveries()) {
                deadLetter(event, "MAX_DELIVERIES_EXCEEDED");
            } else {
                events.add(event);
            }
        }
        return events;
    }

    @Override
    public void acknowledge(QueuedEvent event) {
        streams.acknowledge(properties.stream(), properties.group(), RecordId.of(event.recordId()));
    }

    @Override
    public void deadLetter(QueuedEvent event, String reason) {
        Map<String, String> parked = new HashMap<>(event.fields());
        parked.put("deadLetterReason", reason);
        parked.put("originalRecordId", event.recordId());
        parked.put("deliveryCount", Long.toString(event.deliveryCount()));
        streams.add(properties.deadLetterStream(), parked,
                XAddOptions.maxlen(DEAD_LETTER_MAX_LENGTH).approximateTrimming(false));
        acknowledge(event);
        LOG.error("Record {} moved to {}: {}", event.recordId(), properties.deadLetterStream(), reason);
    }

    /**
     * Creates the consumer group from the start of the stream, so records published before the first
     * worker existed are evaluated too. Records already trimmed from Redis need the outbox replay
     * described in docs/reliability.md.
     */
    private void ensureGroup() {
        if (groupReady) return;
        try {
            streams.createGroup(properties.stream(), ReadOffset.from("0"), properties.group());
            LOG.info("Created consumer group {} on {}", properties.group(), properties.stream());
        } catch (RuntimeException e) {
            if (!mentions(e, "BUSYGROUP")) throw e;
        }
        groupReady = true;
    }

    private static boolean mentions(Throwable error, String text) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains(text)) return true;
            if (cause.getCause() == cause) break;
        }
        return false;
    }

    private static QueuedEvent event(MapRecord<String, Object, Object> record, long deliveryCount) {
        Map<String, String> fields = new HashMap<>();
        record.getValue().forEach((key, value) -> fields.put(String.valueOf(key), String.valueOf(value)));
        return new QueuedEvent(record.getId().getValue(), fields, deliveryCount);
    }
}
