package org.university.judge.api.assignment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.university.judge.api.persistence.CreatedEntity;

@Entity
@Table(name = "assignment_revisions")
public class AssignmentRevision extends CreatedEntity {
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assignment_id", nullable = false, updatable = false)
    private Assignment assignment;

    @Positive
    @Column(name = "revision_number", nullable = false)
    private int revisionNumber;

    @NotBlank
    @Size(max = 255)
    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @NotNull
    @Column(name = "description", nullable = false, columnDefinition = "text")
    private String description;

    @NotNull
    @PositiveOrZero
    @Digits(integer = 15, fraction = 4)
    @Column(name = "maximum_score", nullable = false, precision = 19, scale = 4)
    private BigDecimal maximumScore;

    @NotNull
    @Column(name = "deadline", nullable = false)
    private Instant deadline;

    @Column(name = "sealed_at")
    private Instant sealedAt;

    protected AssignmentRevision() {
    }

    public AssignmentRevision(Assignment assignment, int revisionNumber) {
        this.assignment = assignment;
        this.revisionNumber = revisionNumber;
        this.title = assignment.getTitle();
        this.description = assignment.getDescription();
        this.maximumScore = assignment.getMaximumScore();
        this.deadline = assignment.getDeadline();
    }

    // Flush draft test cases before sealing. PostgreSQL verifies the point total
    // and serializes sealing with concurrent test case writes.
    public void seal() {
        if (sealedAt != null) {
            throw new IllegalStateException("Assignment revision is already sealed");
        }
        sealedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public Assignment getAssignment() {
        return assignment;
    }

    public int getRevisionNumber() {
        return revisionNumber;
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

    public Instant getSealedAt() {
        return sealedAt;
    }

}
