package org.university.judge.api.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.university.judge.api.persistence.CreatedEntity;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent extends CreatedEntity {
    @NotNull
    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @NotBlank
    @Size(max = 100)
    @Column(name = "event_type", nullable = false, length = 100, updatable = false)
    private String eventType;

    @Positive
    @Column(name = "event_version", nullable = false)
    private int eventVersion;

    @NotNull
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> payload;

    @Column(name = "published_at")
    private Instant publishedAt;

    @PositiveOrZero
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    protected OutboxEvent() {
    }

    public OutboxEvent(UUID aggregateId, String eventType, int eventVersion, Map<String, Object> payload) {
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.eventVersion = eventVersion;
        this.payload = new LinkedHashMap<>(payload);
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public void setAttemptCount(int attemptCount) {
        this.attemptCount = attemptCount;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public int getEventVersion() {
        return eventVersion;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

}
