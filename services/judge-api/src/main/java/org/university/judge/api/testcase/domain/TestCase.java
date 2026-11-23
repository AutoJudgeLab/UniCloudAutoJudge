package org.university.judge.api.testcase.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import org.university.judge.api.assignment.domain.Assignment;
import org.university.judge.api.assignment.domain.AssignmentRevision;
import org.university.judge.api.persistence.CreatedEntity;

@Entity
@Table(name = "test_cases")
public class TestCase extends CreatedEntity {
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assignment_id", nullable = false, updatable = false)
    private Assignment assignment;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assignment_revision_id", nullable = false, updatable = false)
    private AssignmentRevision revision;

    @NotNull
    @Column(name = "input_data", nullable = false, columnDefinition = "text")
    private String inputData;

    @NotNull
    @Column(name = "expected_output", nullable = false, columnDefinition = "text")
    private String expectedOutput;

    @NotNull
    // Legacy revisions may contain zero-point cases. API writes and publication
    // require positive points; retain the historical representation here.
    @PositiveOrZero
    @Digits(integer = 15, fraction = 4)
    @Column(name = "points", nullable = false, precision = 19, scale = 4)
    private BigDecimal points;

    @Column(name = "is_hidden", nullable = false)
    private boolean hidden = true;

    @PositiveOrZero
    @Column(name = "execution_order", nullable = false)
    private int executionOrder;

    protected TestCase() {
    }

    public TestCase(AssignmentRevision revision, String inputData, String expectedOutput,
                    BigDecimal points, boolean hidden, int executionOrder) {
        this.assignment = revision.getAssignment();
        this.revision = revision;
        updateDetails(inputData, expectedOutput, points, hidden, executionOrder);
    }

    public void updateDetails(String inputData, String expectedOutput, BigDecimal points,
                              boolean hidden, int executionOrder) {
        if (revision.getSealedAt() != null) {
            throw new IllegalStateException("Test cases of a sealed revision are immutable");
        }
        this.inputData = inputData;
        this.expectedOutput = expectedOutput;
        this.points = points;
        this.hidden = hidden;
        this.executionOrder = executionOrder;
    }

    public Assignment getAssignment() {
        return assignment;
    }

    public AssignmentRevision getRevision() {
        return revision;
    }

    public String getInputData() {
        return inputData;
    }

    public String getExpectedOutput() {
        return expectedOutput;
    }

    public BigDecimal getPoints() {
        return points;
    }

    public boolean isHidden() {
        return hidden;
    }

    public int getExecutionOrder() {
        return executionOrder;
    }

}
