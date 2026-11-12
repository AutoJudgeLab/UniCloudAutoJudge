package org.university.judge.api.assignment.api;

import java.util.List;
import org.springframework.data.domain.Page;

public record AssignmentPageResponse(List<AssignmentResponse> content, int page, int size, long totalElements, int totalPages) {
    public static AssignmentPageResponse from(Page<AssignmentResponse> page) {
        return new AssignmentPageResponse(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
