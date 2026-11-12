package org.university.judge.api.assignment.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.university.judge.api.assignment.domain.AssignmentStatus;

public record AssignmentListRequest(
        @Min(0) @Schema(defaultValue = "0") Integer page,
        @Min(1) @Max(100) @Schema(defaultValue = "20") Integer size,
        @Pattern(regexp = "(id|title|maximumScore|deadline|status|createdAt|updatedAt),(asc|desc)")
        @Schema(defaultValue = "createdAt,desc", description = "Allowed field,direction; ID breaks ties") String sort,
        @Schema(description = "Optional status filter, always intersected with the caller's visibility") AssignmentStatus status) {
    public AssignmentListRequest {
        page = page == null ? 0 : page;
        size = size == null ? 20 : size;
        sort = sort == null ? "createdAt,desc" : sort;
    }
}
