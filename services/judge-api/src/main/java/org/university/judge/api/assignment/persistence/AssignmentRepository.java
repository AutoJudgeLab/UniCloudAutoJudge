package org.university.judge.api.assignment.persistence;

import java.util.UUID;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.university.judge.api.assignment.domain.Assignment;
import org.university.judge.api.assignment.domain.AssignmentStatus;

public interface AssignmentRepository extends JpaRepository<Assignment, UUID>, JpaSpecificationExecutor<Assignment> {
    Page<Assignment> findByTeacherId(UUID teacherId, Pageable pageable);
    Page<Assignment> findByStatus(AssignmentStatus status, Pageable pageable);

    // Full PostgreSQL row lock also serializes concurrent foreign-key inserts.
    @Query(value = "SELECT a.* FROM assignments a WHERE a.id = :id FOR UPDATE", nativeQuery = true)
    Optional<Assignment> findByIdForUpdate(@Param("id") UUID id);

    @Query(value = "SELECT a.* FROM assignments a WHERE a.id = :id FOR SHARE", nativeQuery = true)
    Optional<Assignment> findByIdForShare(@Param("id") UUID id);
}
