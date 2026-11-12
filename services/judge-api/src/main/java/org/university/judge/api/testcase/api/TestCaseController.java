package org.university.judge.api.testcase.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.university.judge.api.testcase.application.TestCaseService;
import org.university.judge.api.testcase.application.TestCaseView;

@RestController
@RequestMapping("/api/assignments/{assignmentId}/test-cases")
@Tag(name = "Test cases", description = "Current grading revision. Writes on published/sealed revisions fork a new draft; reload IDs after a fork.")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "400", description = "Invalid test case, UUID or JSON",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "401", description = "Authentication required",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "403", description = "Role or ownership forbids the operation",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "404", description = "Assignment/test missing, hidden, or test ID belongs to a different assignment or revision",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "409", description = "Duplicate execution order, archived assignment, or concurrent conflict",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class TestCaseController {
    private final TestCaseService testCases;
    public TestCaseController(TestCaseService testCases) { this.testCases = testCases; }

    @PostMapping
    @Operation(summary = "Create a test case", description = "Owner TEACHER or ADMIN. Published/sealed grading is copied into a new DRAFT before modification.")
    @ApiResponse(responseCode = "201", description = "Created in the current revision",
            headers = @Header(name = "X-Assignment-Revision", description = "Current grading revision number", schema = @Schema(type = "integer")))
    public ResponseEntity<TestCaseResponse> create(@PathVariable UUID assignmentId, @Valid @RequestBody TestCaseRequest request) {
        var result = testCases.create(assignmentId, request);
        return ResponseEntity.created(URI.create("/api/assignments/" + assignmentId + "/test-cases/" + result.body().id()))
                .header("X-Assignment-Revision", Integer.toString(result.revisionNumber())).body(result.body());
    }

    @GetMapping
    @Operation(summary = "List test cases in execution order", description = "Owner TEACHER or ADMIN sees all current cases. STUDENT sees only public cases on a published, sealed revision.")
    public ResponseEntity<List<TestCaseResponse>> list(@PathVariable UUID assignmentId) { return response(testCases.list(assignmentId)); }

    @GetMapping("/{testCaseId}")
    @Operation(summary = "Get a visible current test case", description = "Hidden cases return 404 to students. Historical IDs are not exposed by these routes.")
    public ResponseEntity<TestCaseResponse> get(@PathVariable UUID assignmentId, @PathVariable UUID testCaseId) {
        return response(testCases.get(assignmentId, testCaseId));
    }

    @PutMapping("/{testCaseId}")
    @Operation(summary = "Replace a test case", description = "Owner TEACHER or ADMIN. On a published/sealed revision, copies all tests to a new draft and edits the copy. Returns its new ID; old IDs become stale.")
    public ResponseEntity<TestCaseResponse> update(@PathVariable UUID assignmentId, @PathVariable UUID testCaseId,
            @Valid @RequestBody TestCaseRequest request) { return response(testCases.update(assignmentId, testCaseId, request)); }

    @DeleteMapping("/{testCaseId}")
    @Operation(summary = "Delete a test case from the draft", description = "Owner TEACHER or ADMIN. Published/sealed tests are retained; deletion applies to a new draft copy.")
    @ApiResponse(responseCode = "204", description = "Removed from draft; reload IDs when the revision changes", content = @Content,
            headers = @Header(name = "X-Assignment-Revision", description = "Current grading revision number", schema = @Schema(type = "integer")))
    public ResponseEntity<Void> delete(@PathVariable UUID assignmentId, @PathVariable UUID testCaseId) {
        return ResponseEntity.noContent().header("X-Assignment-Revision", Integer.toString(testCases.delete(assignmentId, testCaseId))).build();
    }

    private <T> ResponseEntity<T> response(TestCaseView<T> result) {
        return ResponseEntity.ok().header("X-Assignment-Revision", Integer.toString(result.revisionNumber())).body(result.body());
    }
}
