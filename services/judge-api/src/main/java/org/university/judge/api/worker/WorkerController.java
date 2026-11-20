package org.university.judge.api.worker;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.university.judge.api.result.Verdict;
import org.university.judge.api.result.application.EvaluationCompletion;
import org.university.judge.api.result.application.EvaluationCompletionService;

@Hidden
@RestController
@RequestMapping("/internal/worker/v1/submissions")
public class WorkerController {
    private final WorkerTransportService transport;
    private final EvaluationCompletionService completion;

    public WorkerController(WorkerTransportService transport, EvaluationCompletionService completion) {
        this.transport = transport; this.completion = completion;
    }

    public record BeginRequest(@NotNull UUID attemptId, @NotNull UUID eventId) { }
    public record ResultRequest(@NotNull Verdict verdict, @NotNull BigDecimal totalScore,
            @NotNull Integer passedTests, @NotNull Integer totalTests, Long executionTimeMs,
            @NotNull List<@NotNull @Valid TestResult> tests) {
        public record TestResult(@NotNull UUID testCaseId, @NotNull Verdict status,
                @NotNull BigDecimal awardedPoints, Long executionTimeMs, String actualOutput) { }
    }

    @PostMapping("/{submissionId}/attempts")
    public ResponseEntity<WorkerJob> begin(@PathVariable UUID submissionId, @Valid @RequestBody BeginRequest request) {
        var job = transport.begin(submissionId, request.attemptId(), request.eventId());
        return ResponseEntity.status(job == null ? 204 : 200).cacheControl(CacheControl.noStore()).body(job);
    }

    @PostMapping("/{submissionId}/attempts/{attemptId}/result")
    public ResponseEntity<Void> complete(@PathVariable UUID submissionId, @PathVariable UUID attemptId,
            @Valid @RequestBody ResultRequest request) {
        completion.complete(new EvaluationCompletion(submissionId, attemptId, request.verdict(), request.totalScore(),
                request.passedTests(), request.totalTests(), request.executionTimeMs(), request.tests().stream()
                .map(test -> new EvaluationCompletion.TestOutcome(test.testCaseId(), test.status(), test.awardedPoints(),
                        test.executionTimeMs(), test.actualOutput())).toList()));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    public record LeaseResponse(long leaseMillis) { }

    @PostMapping("/{submissionId}/attempts/{attemptId}/heartbeat")
    public ResponseEntity<LeaseResponse> heartbeat(@PathVariable UUID submissionId, @PathVariable UUID attemptId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new LeaseResponse(transport.heartbeat(submissionId, attemptId)));
    }
}
