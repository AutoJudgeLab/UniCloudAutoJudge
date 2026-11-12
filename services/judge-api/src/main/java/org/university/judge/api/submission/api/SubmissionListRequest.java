package org.university.judge.api.submission.api;

import java.util.UUID;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Pattern;
import org.university.judge.api.submission.domain.ProgrammingLanguage;
import org.university.judge.api.submission.domain.SubmissionStatus;
import io.swagger.v3.oas.annotations.media.Schema;

public record SubmissionListRequest(
        @Min(0) @Schema(defaultValue = "0") Integer page,
        @Min(1) @Max(100) @Schema(defaultValue = "20") Integer size,
        @Pattern(regexp = "(id|submittedAt|startedAt|completedAt|status|programmingLanguage),(asc|desc)")
        @Schema(defaultValue = "submittedAt,desc") String sort,
        SubmissionStatus status, ProgrammingLanguage programmingLanguage,
        @Schema(description = "Optional assignment filter, intersected with ownership and any assignment path") UUID assignmentId,
        @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
        @Schema(description = "Inclusive submittedAt lower bound, ISO-8601 instant") java.time.Instant submittedFrom,
        @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
        @Schema(description = "Exclusive submittedAt upper bound, ISO-8601 instant") java.time.Instant submittedBefore) {
    @jakarta.validation.constraints.AssertTrue(message = "submittedFrom must be earlier than submittedBefore")
    public boolean isDateRangeValid() {
        return submittedFrom == null || submittedBefore == null || submittedFrom.isBefore(submittedBefore);
    }
    public SubmissionListRequest {
        page = page == null ? 0 : page;
        size = size == null ? 20 : size;
        sort = sort == null ? "submittedAt,desc" : sort;
    }
}
