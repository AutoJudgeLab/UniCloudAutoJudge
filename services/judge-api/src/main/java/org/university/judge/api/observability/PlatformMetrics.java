package org.university.judge.api.observability;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.university.judge.api.outbox.OutboxProperties;
@Component
public class PlatformMetrics {
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final OutboxProperties properties;
    private volatile double accepted = Double.NaN, pending = Double.NaN, failed = Double.NaN, backlog = Double.NaN,
            oldest = Double.NaN, streamLength = Double.NaN, lastSuccess = Double.NaN;
    public PlatformMetrics(JdbcTemplate jdbc, StringRedisTemplate redis, OutboxProperties properties, MeterRegistry meters) {
        this.jdbc = jdbc; this.redis = redis; this.properties = properties;
        Gauge.builder("judge.submissions.accepted", this, value -> value.accepted).register(meters);
        Gauge.builder("judge.submissions.pending", this, value -> value.pending).register(meters);
        Gauge.builder("judge.submissions.failed", this, value -> value.failed).register(meters);
        Gauge.builder("judge.outbox.backlog", this, value -> value.backlog).register(meters);
        Gauge.builder("judge.outbox.oldest.seconds", this, value -> value.oldest).register(meters);
        Gauge.builder("judge.redis.stream.length", this, value -> value.streamLength).register(meters);
        Gauge.builder("judge.metrics.snapshot.timestamp", this, value -> value.lastSuccess).register(meters);
    }
    @Scheduled(fixedDelayString = "${observability.snapshot-interval:PT30S}")
    public void refresh() {
        try {
            var submissions = jdbc.queryForMap("SELECT count(*) AS accepted, count(*) FILTER (WHERE status = 'PENDING') AS pending, count(*) FILTER (WHERE status = 'FAILED') AS failed FROM submissions");
            accepted = ((Number) submissions.get("accepted")).doubleValue();
            pending = ((Number) submissions.get("pending")).doubleValue(); failed = ((Number) submissions.get("failed")).doubleValue();
            var events = jdbc.queryForMap("SELECT count(*) AS backlog, COALESCE(extract(epoch FROM clock_timestamp() - min(created_at)), 0) AS oldest FROM outbox_events WHERE published_at IS NULL");
            backlog = ((Number) events.get("backlog")).doubleValue(); oldest = ((Number) events.get("oldest")).doubleValue();
            lastSuccess = System.currentTimeMillis() / 1000d;
        } catch (RuntimeException ignored) { accepted = pending = failed = backlog = oldest = Double.NaN; }
        try { Long size = redis.opsForStream().size(properties.stream()); streamLength = size == null ? Double.NaN : size; }
        catch (RuntimeException ignored) { streamLength = Double.NaN; }
    }
}

