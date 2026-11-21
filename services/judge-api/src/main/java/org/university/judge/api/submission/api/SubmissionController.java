package org.university.judge.api.submission.api;

import java.net.URI;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.university.judge.api.submission.application.SubmissionQueries;
import org.university.judge.api.submission.application.SubmissionService;
import org.university.judge.api.submission.domain.SubmissionException;

@RestController
@Tag(name = "Submissions")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "400", description = "Invalid request, language, file or idempotency key", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "403", description = "Only students can submit", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "404", description = "Assignment/submission missing or not visible", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "409", description = "Idempotency conflict, deadline, publication, grading or concurrency conflict", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "413", description = "Upload too large", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "503", description = "Storage/database unavailable; retry the same request and key", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
public class SubmissionController {
    private final SubmissionService service;
    private final SubmissionQueries queries;
    public SubmissionController(SubmissionService service, SubmissionQueries queries) { this.service = service; this.queries = queries; }

    @PostMapping(value = "/api/assignments/{assignmentId}/submissions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('STUDENT')")
    @Operation(summary = "Submit source code", description = "Requires exactly file and programmingLanguage parts. Upload must finish before the server-side acceptance deadline; 201 follows the database commit. Equivalent idempotent retries return the original acceptance response, including after the deadline.")
    @ApiResponse(responseCode = "201", description = "Source, submission, and outbox committed (or original acceptance replayed)")
    public ResponseEntity<SubmissionCreatedResponse> submit(@PathVariable UUID assignmentId,
            @Parameter(description = "Required, scoped to the authenticated student; 1–128 letters/digits/._:-") @RequestHeader("Idempotency-Key") String key,
            @RequestPart("file") MultipartFile file,
            @Parameter(schema = @Schema(allowableValues = {"JAVA", "PYTHON", "C", "CPP", "JAVASCRIPT", "TYPESCRIPT", "KOTLIN", "GO", "RUST", "CSHARP"}))
            @RequestPart("programmingLanguage") String programmingLanguage, HttpServletRequest request) throws Exception {
        var parts = request.getParts();
        if ((request.getQueryString() != null && !request.getQueryString().isEmpty()) || parts.size() != 2
                || parts.stream().filter(part -> part.getName().equals("file")).count() != 1
                || parts.stream().filter(part -> part.getName().equals("programmingLanguage")).count() != 1) {
            throw new SubmissionException(SubmissionException.Code.INVALID_SUBMISSION_REQUEST);
        }
        var accepted = service.submit(assignmentId, key, programmingLanguage, file);
        return ResponseEntity.created(URI.create("/api/submissions/" + accepted.submissionId())).body(accepted);
    }

    @GetMapping("/api/submissions/{submissionId}")
    @Operation(summary = "Read a private submission", description = "Owner STUDENT, assignment owner TEACHER, or ADMIN. Source keys and source content are not returned.")
    @ApiResponse(responseCode = "200", description = "Visible submission", content = @Content(mediaType = "application/json", schema = @Schema(implementation = SubmissionResponse.class)))
    public SubmissionResponse get(@PathVariable UUID submissionId) { return queries.get(submissionId); }

    @GetMapping("/api/users/me/submissions")
    @Operation(summary = "List the authenticated user's own submissions")
    @ApiResponse(responseCode = "200", description = "Own submissions page", content = @Content(mediaType = "application/json", schema = @Schema(implementation = SubmissionPageResponse.class)))
    public SubmissionPageResponse mine(@Valid @ModelAttribute @ParameterObject SubmissionListRequest request) { return queries.mine(request); }

    @GetMapping("/api/assignments/{assignmentId}/submissions")
    @Operation(summary = "List submissions for an assignment", description = "Students see their own submissions only; owning teachers and administrators see all. Historical access remains available after archiving.")
    @ApiResponse(responseCode = "200", description = "Visible submissions page", content = @Content(mediaType = "application/json", schema = @Schema(implementation = SubmissionPageResponse.class)))
    public SubmissionPageResponse forAssignment(@PathVariable UUID assignmentId, @Valid @ModelAttribute @ParameterObject SubmissionListRequest request) {
        return queries.forAssignment(assignmentId, request);
    }
}
