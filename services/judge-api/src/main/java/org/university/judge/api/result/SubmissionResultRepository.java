package org.university.judge.api.result;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubmissionResultRepository extends JpaRepository<SubmissionResult, UUID> {
    Optional<SubmissionResult> findBySubmissionId(UUID submissionId);
}
