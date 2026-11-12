package org.university.judge.api.testcase.api;

import java.math.BigDecimal;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record TestCaseRequest(
        @NotNull @Schema(description = "Required; empty string is valid") String inputData,
        @NotNull @Schema(description = "Required; empty string is valid") String expectedOutput,
        @NotNull @Positive @Digits(integer = 15, fraction = 4) BigDecimal points,
        @Schema(defaultValue = "true", description = "Hidden from students; defaults to true") Boolean isHidden,
        @NotNull @Min(0) @Schema(description = "Unique zero-based order within the current revision; gaps are allowed") Integer executionOrder) {
    public TestCaseRequest {
        isHidden = isHidden == null ? true : isHidden;
    }
}
