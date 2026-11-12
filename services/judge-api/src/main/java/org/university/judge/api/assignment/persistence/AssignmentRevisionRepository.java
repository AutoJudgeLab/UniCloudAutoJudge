package org.university.judge.api.assignment.persistence;

import java.util.Optional;
import java.util.UUID;
import java.util.List;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.university.judge.api.assignment.domain.AssignmentRevision;

public interface AssignmentRevisionRepository extends JpaRepository<AssignmentRevision, UUID> {
    Optional<AssignmentRevision> findByAssignmentIdAndRevisionNumber(UUID assignmentId, int revisionNumber);
    Optional<AssignmentRevision> findFirstByAssignmentIdAndSealedAtIsNotNullOrderByRevisionNumberDesc(UUID assignmentId);
    Optional<AssignmentRevision> findFirstByAssignmentIdOrderByRevisionNumberDesc(UUID assignmentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<AssignmentRevision> findByAssignmentIdOrderByRevisionNumber(UUID assignmentId);

    void deleteAllByAssignmentId(UUID assignmentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from AssignmentRevision r where r.id = :id")
    Optional<AssignmentRevision> findByIdForUpdate(@Param("id") UUID id);
}
