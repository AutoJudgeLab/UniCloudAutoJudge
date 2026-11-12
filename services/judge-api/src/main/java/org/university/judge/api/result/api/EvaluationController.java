package org.university.judge.api.result.api;

import java.util.UUID;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import org.university.judge.api.result.application.EvaluationQueries;

@RestController
@Tag(name = "Evaluation results")
@SecurityRequirement(name = "bearerAuth")
public class EvaluationController {
    private final EvaluationQueries queries;
    public EvaluationController(EvaluationQueries queries) { this.queries = queries; }

    @GetMapping("/api/submissions/{submissionId}/result")
    @Operation(summary = "Read a submission's evaluation", description = "Owner student, assignment owner teacher, or administrator. Unavailable grades are null. Hidden inputs and outputs are null for students; historical revision visibility is authoritative.")
    @ApiResponse(responseCode = "200", description = "Current status and available evaluation")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "404", description = "Submission missing or not visible")
    public EvaluationResponse get(@PathVariable UUID submissionId) { return queries.get(submissionId); }
}

