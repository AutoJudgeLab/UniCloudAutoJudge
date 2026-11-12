package org.university.judge.api.assignment.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.university.judge.api.assignment.domain.Assignment;
import org.university.judge.api.assignment.domain.AssignmentStatus;

public record AssignmentResponse(UUID id, String title, String description, BigDecimal maximumScore,
                                 Instant deadline, AssignmentStatus status, UUID teacherId,
                                 Instant createdAt, Instant updatedAt) {
    public static AssignmentResponse from(Assignment assignment) {
        return new AssignmentResponse(assignment.getId(), assignment.getTitle(), assignment.getDescription(),
                assignment.getMaximumScore(), assignment.getDeadline(), assignment.getStatus(),
                assignment.getTeacher().getId(), assignment.getCreatedAt(), assignment.getUpdatedAt());
    }
}
