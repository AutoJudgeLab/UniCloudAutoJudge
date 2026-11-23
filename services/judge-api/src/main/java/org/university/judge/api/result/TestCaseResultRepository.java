package org.university.judge.api.result;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestCaseResultRepository extends JpaRepository<TestCaseResult, UUID> {
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "testCase")
    List<TestCaseResult> findBySubmissionIdOrderByTestCaseExecutionOrder(UUID submissionId);
}
