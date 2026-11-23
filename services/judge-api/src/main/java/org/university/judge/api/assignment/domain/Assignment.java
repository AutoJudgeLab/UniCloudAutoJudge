package org.university.judge.api.assignment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.university.judge.api.persistence.CreatedEntity;
import org.university.judge.api.user.User;

@Entity
@Table(name = "assignments")
public class Assignment extends CreatedEntity {
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "teacher_id", nullable = false, updatable = false)
    private User teacher;

    @NotBlank
    @Size(max = 255)
    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @NotBlank
    @Column(name = "description", nullable = false, columnDefinition = "text")
    private String description;

    @NotNull
    @Positive
    @Digits(integer = 15, fraction = 4)
    @Column(name = "maximum_score", nullable = false, precision = 19, scale = 4)
    private BigDecimal maximumScore;

    @NotNull
    @Column(name = "deadline", nullable = false)
    private Instant deadline;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AssignmentStatus status = AssignmentStatus.DRAFT;

    @NotNull
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Assignment() {
    }

    public Assignment(User teacher, String title, String description, BigDecimal maximumScore, Instant deadline) {
        this.teacher = teacher;
        updateDetails(title, description, maximumScore, deadline);
    }

    public void updateDetails(String title, String description, BigDecimal maximumScore, Instant deadline) {
        this.title = title;
        this.description = description;
        this.maximumScore = maximumScore;
        this.deadline = deadline;
    }

    public void setStatus(AssignmentStatus status) {
        this.status = status;
    }

    @PrePersist
    private void initializeUpdatedAt() {
        updatedAt = getCreatedAt();
    }

    @PreUpdate
    private void updateTimestamp() {
        updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public User getTeacher() {
        return teacher;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getMaximumScore() {
        return maximumScore;
    }

    public Instant getDeadline() {
        return deadline;
    }

    public AssignmentStatus getStatus() {
        return status;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

}
