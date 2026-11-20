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
import org.springframework.data.redis.core.script.DefaultRedisScript;
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

    private final StreamOperations<String, Object, Object> streams;
    private final WorkerProperties properties;
    private final StringRedisTemplate redis;
    private String pendingCursor;

    private static final DefaultRedisScript<Long> TOUCH = new DefaultRedisScript<>("""
            local p = redis.call('XPENDING', KEYS[1], ARGV[1], ARGV[3], ARGV[3], 1)
            if #p == 0 or p[1][2] ~= ARGV[2] then return 0 end
            redis.call('XCLAIM', KEYS[1], ARGV[1], ARGV[2], 0, ARGV[3], 'IDLE', 0, 'JUSTID')
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> PARK = new DefaultRedisScript<>("""
            local p = redis.call('XPENDING', KEYS[1], ARGV[1], ARGV[3], ARGV[3], 1)
            if #p == 0 or p[1][2] ~= ARGV[2] then return 0 end
            redis.call('XADD', KEYS[2], 'MAXLEN', 10000, '*', unpack(ARGV, 4))
            redis.call('XACK', KEYS[1], ARGV[1], ARGV[3])
            return 1
            """, Long.class);

    public RedisStreamQueue(StringRedisTemplate redis, WorkerProperties properties) {
        this.streams = redis.opsForStream();
        this.redis = redis;
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
        Range<String> range = pendingCursor == null ? Range.unbounded()
                : Range.rightUnbounded(Range.Bound.exclusive(pendingCursor));
        PendingMessages pending = streams.pending(properties.stream(), properties.group(), range, PENDING_SCAN);
        if (pending == null) return events;
        if (pending.isEmpty()) pendingCursor = null;
        int scanned = 0;
        for (PendingMessage message : pending) {
            if (events.size() >= max) break;
            scanned++;
            String id = message.getIdAsString();
            pendingCursor = id;
            if (inFlight.contains(id) || message.getElapsedTimeSinceLastDelivery().compareTo(properties.reclaimIdle()) < 0) {
                continue;
            }
            // XCLAIM succeeds only if the record is still idle, so two workers cannot both take it.
            List<MapRecord<String, Object, Object>> claimed = streams.claim(properties.stream(), properties.group(),
                    properties.consumerName(), properties.reclaimIdle(), message.getId());
            if (claimed == null || claimed.isEmpty()) continue;
            QueuedEvent event = event(claimed.get(0), message.getTotalDeliveryCount() + 1);
            // Transport outages and a live API lease are not grading failures. Only the processor
            // can decide to finalize a failed evaluation; the queue must not discard pending work.
            events.add(event);
        }
        if (scanned == pending.size() && scanned < PENDING_SCAN) pendingCursor = null;
        return events;
    }

    @Override
    public void touch(QueuedEvent event) {
        redis.execute(TOUCH, List.of(properties.stream()), properties.group(), properties.consumerName(), event.recordId());
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
        List<String> arguments = new ArrayList<>(List.of(properties.group(), properties.consumerName(), event.recordId()));
        parked.forEach((key, value) -> { arguments.add(key); arguments.add(value); });
        Long moved = redis.execute(PARK, List.of(properties.stream(), properties.deadLetterStream()), arguments.toArray());
        if (Long.valueOf(1).equals(moved)) {
            LOG.error("Record {} moved to {}: {}", event.recordId(), properties.deadLetterStream(), reason);
        }
    }

    /**
     * Creates the consumer group from the start of the stream, so records published before the first
     * worker existed are evaluated too. Records already trimmed from Redis need the outbox replay
     * described in docs/reliability.md.
     */
    private void ensureGroup() {
        // Idempotent even after FLUSH/restore: no JVM cache can outlive the Redis group.
        try {
            streams.createGroup(properties.stream(), ReadOffset.from("0"), properties.group());
            LOG.info("Created consumer group {} on {}", properties.group(), properties.stream());
        } catch (RuntimeException e) {
            if (!mentions(e, "BUSYGROUP")) throw e;
        }
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
