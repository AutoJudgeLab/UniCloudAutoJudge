package org.university.judge.api.submission.persistence;

import java.util.UUID;
import org.university.judge.api.submission.domain.Submission;
import org.university.judge.api.submission.domain.SubmissionStatus;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubmissionRepository extends JpaRepository<Submission, UUID>, JpaSpecificationExecutor<Submission> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select s from Submission s where s.id = :id")
    java.util.Optional<Submission> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);
    boolean existsByAssignmentId(UUID assignmentId);
    boolean existsByAssignmentIdAndStudentId(UUID assignmentId, UUID studentId);
    Page<Submission> findByStudentIdOrderBySubmittedAtDesc(UUID studentId, Pageable pageable);
    Page<Submission> findByAssignmentIdOrderBySubmittedAtDesc(UUID assignmentId, Pageable pageable);
    Page<Submission> findByStatusOrderBySubmittedAt(SubmissionStatus status, Pageable pageable);
}
