package org.university.judge.api.persistence;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;

@MappedSuperclass
public abstract class CreatedEntity extends UuidEntity {
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void initializeCreatedAt() {
        createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
