package org.university.judge.api.assignment.api;

import java.math.BigDecimal;
import java.time.Instant;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record AssignmentRequest(
        @NotBlank @Size(max = 255) String title,
        @NotBlank String description,
        @NotNull @Positive @Digits(integer = 15, fraction = 4) BigDecimal maximumScore,
        @NotNull @Future @Schema(description = "Future ISO-8601 timestamp with offset; stored as UTC") Instant deadline) {
    public AssignmentRequest {
        title = title == null ? null : title.strip();
    }
}
