package org.university.judge.api.submission.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.university.judge.api.assignment.domain.Assignment;
import org.university.judge.api.assignment.domain.AssignmentRevision;
import java.util.UUID;
import jakarta.persistence.Id;
import jakarta.persistence.Transient;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import org.springframework.data.domain.Persistable;
import org.university.judge.api.user.User;

@Entity
@Table(name = "submissions")
public class Submission implements Persistable<UUID> {
    @jakarta.persistence.Version
    @Column(nullable = false)
    private long version;

    @Column(name = "evaluation_attempt_id")
    private UUID evaluationAttemptId;

    @Column(name = "evaluation_fingerprint", length = 64)
    private String evaluationFingerprint;

    public long getVersion() { return version; }
    public UUID getEvaluationAttemptId() { return evaluationAttemptId; }
    public String getEvaluationFingerprint() { return evaluationFingerprint; }

    public void beginEvaluation(UUID attemptId, Instant now) {
        evaluationAttemptId = attemptId;
        status = SubmissionStatus.RUNNING;
        startedAt = now;
        attemptCount = Math.addExact(attemptCount, 1);
    }

    public void completeEvaluation(String fingerprint, Instant now) {
        evaluationFingerprint = fingerprint;
        status = SubmissionStatus.COMPLETED;
        completedAt = now;
    }
    @Id
    @Column(nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    @Transient
    private boolean newEntity = true;

    @Override public UUID getId() { return id; }
    @Override public boolean isNew() { return newEntity; }
    @PostLoad @PostPersist private void markPersisted() { newEntity = false; }

    @Column(name = "source_size", updatable = false)
    private Long sourceSize;

    @Column(name = "source_sha256", length = 64, updatable = false)
    private String sourceSha256;
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assignment_id", nullable = false, updatable = false)
    private Assignment assignment;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assignment_revision_id", nullable = false, updatable = false)
    private AssignmentRevision revision;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false, updatable = false)
    private User student;

    @NotBlank
    @Size(max = 1024)
    @Column(name = "source_file_key", nullable = false, length = 1024, updatable = false)
    private String sourceFileKey;

    @NotBlank
    @Size(max = 255)
    @Column(name = "original_filename", nullable = false, length = 255, updatable = false)
    private String originalFilename;

    @NotBlank
    @Size(max = 64)
    @Column(name = "programming_language", nullable = false, length = 64, updatable = false)
    private String programmingLanguage;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SubmissionStatus status = SubmissionStatus.PENDING;

    @NotNull
    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @PositiveOrZero
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    protected Submission() {
    }

    public Submission(AssignmentRevision revision, User student, String sourceFileKey,
                      String originalFilename, String programmingLanguage) {
        this.assignment = revision.getAssignment();
        this.revision = revision;
        this.student = student;
        this.sourceFileKey = sourceFileKey;
        this.originalFilename = originalFilename;
        this.programmingLanguage = programmingLanguage;
    }

    @PrePersist
    private void initializeSubmittedAt() {
        if (submittedAt == null) submittedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public Submission(UUID id, AssignmentRevision revision, User student, String key, String filename,
                      String language, Instant acceptedAt, long size, String sha256) {
        this(revision, student, key, filename, language);
        this.id = id;
        this.submittedAt = acceptedAt;
        this.sourceSize = size;
        this.sourceSha256 = sha256;
    }

    public Long getSourceSize() { return sourceSize; }
    public String getSourceSha256() { return sourceSha256; }

    public void setStatus(SubmissionStatus status) {
        this.status = status;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public void setAttemptCount(int attemptCount) {
        this.attemptCount = attemptCount;
    }

    public Assignment getAssignment() {
        return assignment;
    }

    public AssignmentRevision getRevision() {
        return revision;
    }

    public User getStudent() {
        return student;
    }

    public String getSourceFileKey() {
        return sourceFileKey;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getProgrammingLanguage() {
        return programmingLanguage;
    }

    public SubmissionStatus getStatus() {
        return status;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

}
