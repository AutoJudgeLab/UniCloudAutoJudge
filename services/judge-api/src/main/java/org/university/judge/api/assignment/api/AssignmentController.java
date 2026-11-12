package org.university.judge.api.assignment.api;

import java.net.URI;
import java.util.UUID;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.university.judge.api.assignment.application.AssignmentResult;
import org.university.judge.api.assignment.application.AssignmentService;

@RestController
@RequestMapping("/api/assignments")
@Tag(name = "Assignments")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "400", description = "Invalid metadata, deadline, UUID or pagination",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "401", description = "Authentication required",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "403", description = "Role or ownership does not permit this operation",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "404", description = "Assignment missing or not visible to this caller",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "409", description = "Archived assignment, required revision, retained history or concurrency conflict",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class AssignmentController {
    private final AssignmentService assignments;

    public AssignmentController(AssignmentService assignments) { this.assignments = assignments; }

    @PostMapping
    @Operation(summary = "Create a draft assignment", description = "TEACHER or ADMIN. Ownership comes from the authenticated identity; client-supplied teacherId and status are rejected.")
    @ApiResponse(responseCode = "201", description = "Draft created",
            headers = @Header(name = "X-Assignment-Revision", description = "Current numbered metadata revision", schema = @Schema(type = "integer")))
    public ResponseEntity<AssignmentResponse> create(@Valid @RequestBody AssignmentRequest request) {
        AssignmentResult result = assignments.create(request);
        return ResponseEntity.created(URI.create("/api/assignments/" + result.assignment().id()))
                .header("X-Assignment-Revision", Integer.toString(result.revisionNumber())).body(result.assignment());
    }

    @GetMapping
    @Operation(summary = "List visible assignments", description = "Students see published assignments only; teachers see their own assignments; administrators see all. Pagination is zero-based.")
    public AssignmentPageResponse list(@Valid @ModelAttribute @ParameterObject AssignmentListRequest request) {
        return assignments.list(request);
    }

    @GetMapping("/{assignmentId}")
    @Operation(summary = "Get a visible assignment")
    public ResponseEntity<AssignmentResponse> get(@PathVariable UUID assignmentId) { return response(assignments.get(assignmentId)); }

    @PutMapping("/{assignmentId}")
    @Operation(summary = "Replace assignment metadata", description = "Owner TEACHER or ADMIN. Published edits require createNewRevision=true and return the assignment to DRAFT; prior revisions are retained and test cases copied unchanged.")
    public ResponseEntity<AssignmentResponse> update(@PathVariable UUID assignmentId,
            @Valid @RequestBody AssignmentRequest request,
            @Parameter(description = "Explicitly start a new draft revision; required for published edits or sealed grading configurations")
            @RequestParam(defaultValue = "false") boolean createNewRevision) {
        return response(assignments.update(assignmentId, request, createNewRevision));
    }

    @DeleteMapping("/{assignmentId}")
    @Operation(summary = "Delete an assignment without retained history", description = "Owner TEACHER or ADMIN. Archived assignments, submissions, or sealed revisions prevent deletion. Use archive to retain history.")
    @ApiResponse(responseCode = "204", description = "Assignment deleted", content = @Content)
    public ResponseEntity<Void> delete(@PathVariable UUID assignmentId) {
        assignments.delete(assignmentId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{assignmentId}/publish")
    @Operation(summary = "Publish and seal assignment grading", description = "Owner TEACHER or ADMIN. Requires valid metadata, a future deadline, at least one valid test case, and positive test points summing exactly to maximumScore. Seals the current grading revision before publication.")
    public ResponseEntity<AssignmentResponse> publish(@PathVariable UUID assignmentId) { return response(assignments.publish(assignmentId)); }

    @PostMapping("/{assignmentId}/archive")
    @Operation(summary = "Archive an assignment", description = "Owner TEACHER or ADMIN. Retains all history, makes the assignment read-only and removes it from student discovery. Repeated archive is idempotent.")
    public ResponseEntity<AssignmentResponse> archive(@PathVariable UUID assignmentId) { return response(assignments.archive(assignmentId)); }

    private ResponseEntity<AssignmentResponse> response(AssignmentResult result) {
        return ResponseEntity.status(HttpStatus.OK).header("X-Assignment-Revision", Integer.toString(result.revisionNumber())).body(result.assignment());
    }
}
