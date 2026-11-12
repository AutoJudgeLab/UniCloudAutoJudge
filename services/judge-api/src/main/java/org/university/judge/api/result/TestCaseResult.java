package org.university.judge.api.result;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import org.university.judge.api.assignment.domain.AssignmentRevision;
import org.university.judge.api.persistence.UuidEntity;
import org.university.judge.api.submission.domain.Submission;
import org.university.judge.api.testcase.domain.TestCase;

@Entity
@Table(name = "test_case_results")
public class TestCaseResult extends UuidEntity {
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "submission_id", nullable = false, updatable = false)
    private Submission submission;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "test_case_id", nullable = false, updatable = false)
    private TestCase testCase;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assignment_revision_id", nullable = false, updatable = false)
    private AssignmentRevision revision;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Verdict status;

    @Column(name = "actual_output", columnDefinition = "text")
    private String actualOutput;

    @PositiveOrZero
    @Column(name = "execution_time_ms")
    private Long executionTimeMs;

    @NotNull
    @PositiveOrZero
    @Digits(integer = 15, fraction = 4)
    @Column(name = "awarded_points", nullable = false, precision = 19, scale = 4)
    private BigDecimal awardedPoints;

    protected TestCaseResult() {
    }

    public TestCaseResult(Submission submission, TestCase testCase, Verdict status,
                          String actualOutput, Long executionTimeMs, BigDecimal awardedPoints) {
        this.submission = submission;
        this.testCase = testCase;
        this.revision = submission.getRevision();
        this.status = status;
        this.actualOutput = actualOutput;
        this.executionTimeMs = executionTimeMs;
        this.awardedPoints = awardedPoints;
    }

    public Submission getSubmission() {
        return submission;
    }

    public TestCase getTestCase() {
        return testCase;
    }

    public AssignmentRevision getRevision() {
        return revision;
    }

    public Verdict getStatus() {
        return status;
    }

    public String getActualOutput() {
        return actualOutput;
    }

    public Long getExecutionTimeMs() {
        return executionTimeMs;
    }

    public BigDecimal getAwardedPoints() {
        return awardedPoints;
    }

}
