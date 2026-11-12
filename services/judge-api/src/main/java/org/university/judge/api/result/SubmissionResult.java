package org.university.judge.api.result;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import org.university.judge.api.persistence.CreatedEntity;
import org.university.judge.api.submission.domain.Submission;

@Entity
@Table(name = "submission_results")
public class SubmissionResult extends CreatedEntity {
    @NotNull
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "submission_id", nullable = false, updatable = false, unique = true)
    private Submission submission;

    @NotNull
    @PositiveOrZero
    @Digits(integer = 15, fraction = 4)
    @Column(name = "total_score", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalScore;

    @NotNull
    @PositiveOrZero
    @Digits(integer = 15, fraction = 4)
    @Column(name = "maximum_score", nullable = false, precision = 19, scale = 4)
    private BigDecimal maximumScore;

    @PositiveOrZero
    @Column(name = "passed_tests", nullable = false)
    private int passedTests;

    @PositiveOrZero
    @Column(name = "total_tests", nullable = false)
    private int totalTests;

    @PositiveOrZero
    @Column(name = "execution_time_ms")
    private Long executionTimeMs;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Verdict verdict;

    protected SubmissionResult() {
    }

    public SubmissionResult(Submission submission, BigDecimal totalScore, int passedTests,
                            int totalTests, Long executionTimeMs, Verdict verdict) {
        this.submission = submission;
        this.totalScore = totalScore;
        this.maximumScore = submission.getRevision().getMaximumScore();
        this.passedTests = passedTests;
        this.totalTests = totalTests;
        this.executionTimeMs = executionTimeMs;
        this.verdict = verdict;
    }

    public Submission getSubmission() {
        return submission;
    }

    public BigDecimal getTotalScore() {
        return totalScore;
    }

    public BigDecimal getMaximumScore() {
        return maximumScore;
    }

    public int getPassedTests() {
        return passedTests;
    }

    public int getTotalTests() {
        return totalTests;
    }

    public Long getExecutionTimeMs() {
        return executionTimeMs;
    }

    public Verdict getVerdict() {
        return verdict;
    }

}
