package org.university.judge.api.testcase.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.university.judge.api.testcase.domain.TestCase;

public interface TestCaseRepository extends JpaRepository<TestCase, UUID> {
    List<TestCase> findByRevisionIdOrderByExecutionOrder(UUID revisionId);
    List<TestCase> findByRevisionIdAndHiddenFalseOrderByExecutionOrder(UUID revisionId);
    Optional<TestCase> findByIdAndRevisionId(UUID id, UUID revisionId);
    Optional<TestCase> findByIdAndRevisionIdAndHiddenFalse(UUID id, UUID revisionId);
    Optional<TestCase> findByRevisionIdAndExecutionOrder(UUID revisionId, int executionOrder);
    void deleteAllByAssignmentId(UUID assignmentId);
}
