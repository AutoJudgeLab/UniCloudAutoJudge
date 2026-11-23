package org.university.judge.api.leaderboard.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import io.swagger.v3.oas.annotations.media.Schema;

public record LeaderboardRequest(@Min(0) @Schema(defaultValue = "0") Integer page,
                                 @Min(1) @Max(100) @Schema(defaultValue = "20") Integer size) {
    public LeaderboardRequest {
        page = page == null ? 0 : page;
        size = size == null ? 20 : size;
    }
}

