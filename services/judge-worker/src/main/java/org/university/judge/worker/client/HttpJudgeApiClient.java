package org.university.judge.worker.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.university.judge.worker.config.WorkerProperties;
import org.university.judge.worker.grading.Verdict;
import org.university.judge.worker.model.EvaluationReport;
import org.university.judge.worker.model.JudgeJob;
import org.university.judge.worker.model.TestOutcome;

/**
 * HTTP implementation of the worker transport specified in docs/worker-transport.md.
 *
 * The API authenticates a separate evaluator credential, returns the sealed job and persists results.
 */
@Component
public class HttpJudgeApiClient implements JudgeApiClient {

    private final HttpClient http;
    private final WorkerProperties.Api api;

    public HttpJudgeApiClient(WorkerProperties properties) {
        this.api = properties.api();
        this.http = HttpClient.newBuilder().connectTimeout(api.connectTimeout()).build();
    }

    @Override
    public BeginResult begin(UUID submissionId, UUID attemptId, UUID eventId) {
        HttpResponse<String> response = post("/internal/worker/v1/submissions/" + submissionId + "/attempts",
                new BeginRequest(attemptId, eventId));
        return switch (response.statusCode()) {
            case 200 -> BeginResult.started(Json.read(response.body(), JudgeJob.class));
            case 204 -> BeginResult.alreadyCompleted();
            case 409 -> BeginResult.busy();
            case 400, 404, 410, 422 -> throw new PermanentApiException("Begin rejected with HTTP " + response.statusCode());
            default -> throw new ApiException("Begin failed with HTTP " + response.statusCode());
        };
    }

    @Override
    public void complete(EvaluationReport report) {
        HttpResponse<String> response = post("/internal/worker/v1/submissions/" + report.submissionId() + "/attempts/"
                + report.attemptId() + "/result", new ResultRequest(report.verdict(), report.totalScore().toPlainString(),
                report.passedTests(), report.totalTests(), report.executionTimeMs(),
                report.tests().stream().map(ResultRequest.Test::of).toList()));
        int status = response.statusCode();
        if (status >= 200 && status < 300) return;
        if (status == 409) throw new StaleAttemptException("Attempt " + report.attemptId() + " is no longer current");
        if (status == 400 || status == 404 || status == 422) {
            throw new PermanentApiException("Result rejected with HTTP " + status);
        }
        throw new ApiException("Result upload failed with HTTP " + status);
    }

    private HttpResponse<String> post(String path, Object body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(api.baseUrl() + path))
                .timeout(api.requestTimeout())
                .header("Authorization", "Bearer " + api.token())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                .build();
        try {
            return http.send(request, BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ApiException("API unreachable: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("Interrupted while calling the API", e);
        }
    }

    @Override
    public long heartbeat(UUID submissionId, UUID attemptId) {
        var response = post("/internal/worker/v1/submissions/" + submissionId + "/attempts/" + attemptId + "/heartbeat", Map.of());
        if (response.statusCode() == 409 || response.statusCode() == 404) {
            throw new StaleAttemptException("Evaluation lease no longer belongs to this attempt");
        }
        if (response.statusCode() != 200) throw new ApiException("Heartbeat failed with HTTP " + response.statusCode());
        long millis = Json.read(response.body(), LeaseResponse.class).leaseMillis();
        if (millis < 1) throw new ApiException("Invalid evaluation lease duration");
        return millis;
    }

    record LeaseResponse(long leaseMillis) { }

    record BeginRequest(UUID attemptId, UUID eventId) { }

    /** Scores travel as decimal strings so no JSON parser can round them. */
    record ResultRequest(Verdict verdict, String totalScore, int passedTests, int totalTests, Long executionTimeMs,
            List<Test> tests) {
        record Test(UUID testCaseId, Verdict status, String awardedPoints, Long executionTimeMs, String actualOutput) {
            static Test of(TestOutcome outcome) {
                return new Test(outcome.testCaseId(), outcome.status(), outcome.awardedPoints().toPlainString(),
                        outcome.executionTimeMs(), outcome.actualOutput());
            }
        }
    }
}
