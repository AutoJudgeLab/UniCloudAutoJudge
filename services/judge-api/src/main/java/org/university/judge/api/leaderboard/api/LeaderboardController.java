package org.university.judge.api.leaderboard.api;

import java.util.UUID;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.*;
import org.university.judge.api.leaderboard.application.LeaderboardService;

@RestController
@Tag(name = "Leaderboards")
@SecurityRequirement(name = "bearerAuth")
public class LeaderboardController {
    private final LeaderboardService service;
    public LeaderboardController(LeaderboardService service) { this.service = service; }

    @GetMapping("/api/assignments/{assignmentId}/leaderboard")
    @Operation(summary = "Read the assignment leaderboard", description = "Authenticated users can view published assignments. Owning teachers and administrators also retain unpublished/history access. Best valid completed score per student, then earliest completion and submission UUID. Fixed ranking order; page size up to 100. Internal errors are excluded.")
    @ApiResponse(responseCode = "200", description = "Paginated ranked students, possibly empty")
    @ApiResponse(responseCode = "400", description = "Invalid pagination")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "404", description = "Assignment missing or not visible")
    public LeaderboardPage get(@PathVariable UUID assignmentId, @Valid @ModelAttribute @ParameterObject LeaderboardRequest request) {
        return service.get(assignmentId, request);
    }
}

