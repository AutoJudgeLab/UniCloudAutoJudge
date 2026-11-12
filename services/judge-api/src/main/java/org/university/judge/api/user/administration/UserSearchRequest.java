package org.university.judge.api.user.administration;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.university.judge.api.user.Role;

public record UserSearchRequest(
        @Min(0) @Schema(defaultValue = "0") Integer page,
        @Min(1) @Max(100) @Schema(defaultValue = "20", maximum = "100") Integer size,
        @Pattern(regexp = "(id|username|email|role|enabled|createdAt),(asc|desc)")
        @Schema(defaultValue = "createdAt,desc", description = "One allowed field and direction; an ID tie-breaker is appended") String sort,
        @Size(max = 100) @Schema(description = "Case-insensitive literal substring; combined with other filters using AND") String username,
        @Size(max = 254) @Schema(description = "Case-insensitive literal substring") String email,
        Role role, Boolean enabled) {
    public UserSearchRequest {
        page = page == null ? 0 : page;
        size = size == null ? 20 : size;
        sort = sort == null ? "createdAt,desc" : sort;
    }
}
