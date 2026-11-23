package org.university.judge.api.submission.api;

import java.util.List;
import org.springframework.data.domain.Page;

public record SubmissionPageResponse(List<SubmissionResponse> content, int page, int size, long totalElements, int totalPages) {
    public static SubmissionPageResponse from(Page<SubmissionResponse> page) {
        return new SubmissionPageResponse(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
