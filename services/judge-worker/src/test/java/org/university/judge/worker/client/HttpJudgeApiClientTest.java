package org.university.judge.worker.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.university.judge.worker.Fixtures;
import org.university.judge.worker.client.JudgeApiClient.BeginResult;
import org.university.judge.worker.grading.Verdict;
import org.university.judge.worker.model.EvaluationReport;
import org.university.judge.worker.model.JudgeJob;
import org.university.judge.worker.model.TestOutcome;

/** The proposed worker transport, exercised against a throw-away local HTTP server. */
class HttpJudgeApiClientTest {

    private record Exchange(String method, String path, String authorization, String body) { }

    private interface Call {
        void with(JudgeApiClient client, AtomicReference<Exchange> seen) throws Exception;
    }

    private static void serve(int status, String responseBody, Call call) throws Exception {
        AtomicReference<Exchange> seen = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seen.set(new Exchange(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            call.with(new HttpJudgeApiClient(Fixtures.properties("docker", baseUrl, List.of("JAVA"))), seen);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void beginSendsTheAttemptAndReadsTheJob() throws Exception {
        UUID submission = UUID.randomUUID();
        UUID attempt = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        UUID test = UUID.randomUUID();
        String job = """
                {"submissionId":"%s","attemptId":"%s","assignmentRevisionId":"%s","programmingLanguage":"JAVA",
                 "originalFilename":"Main.java","source":"public class Main { }\\n","futureField":true,
                 "tests":[{"testCaseId":"%s","inputData":"2 3\\n","expectedOutput":"5\\n","points":12.5000,"executionOrder":0}]}
                """.formatted(submission, attempt, UUID.randomUUID(), test);
        serve(200, job, (client, seen) -> {
            BeginResult result = client.begin(submission, attempt, event);
            assertEquals(BeginResult.Status.STARTED, result.status());
            JudgeJob parsed = result.job();
            assertEquals(attempt, parsed.attemptId());
            assertEquals("public class Main { }\n", parsed.source());
            assertEquals(null, parsed.timeLimitMs());
            assertEquals(test, parsed.tests().get(0).testCaseId());
            assertEquals(0, new BigDecimal("12.5").compareTo(parsed.tests().get(0).points()));

            assertEquals("POST", seen.get().method());
            assertEquals("/internal/worker/v1/submissions/" + submission + "/attempts", seen.get().path());
            assertEquals("Bearer test-token", seen.get().authorization());
            assertTrue(seen.get().body().contains(attempt.toString()));
            assertTrue(seen.get().body().contains(event.toString()));
        });
    }

    @Test
    void beginStatusCodesMapToOutcomes() throws Exception {
        serve(204, "", (client, seen) -> assertEquals(BeginResult.Status.ALREADY_COMPLETED,
                client.begin(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()).status()));
        serve(409, "", (client, seen) -> assertEquals(BeginResult.Status.BUSY,
                client.begin(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()).status()));
        serve(404, "", (client, seen) -> assertThrows(PermanentApiException.class,
                () -> client.begin(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())));
        serve(503, "", (client, seen) -> {
            ApiException transientFailure = assertThrows(ApiException.class,
                    () -> client.begin(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
            assertTrue(!(transientFailure instanceof PermanentApiException));
        });
        serve(401, "", (client, seen) -> assertThrows(ApiException.class,
                () -> client.begin(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())));
    }

    @Test
    void completeSendsTheReportInTheShapeOfEvaluationCompletion() throws Exception {
        UUID test = UUID.randomUUID();
        EvaluationReport report = new EvaluationReport(UUID.randomUUID(), UUID.randomUUID(), Verdict.WRONG_ANSWER,
                new BigDecimal("0"), 0, 1, 87L,
                List.of(new TestOutcome(test, Verdict.WRONG_ANSWER, BigDecimal.ZERO, 87L, "6\n")));
        serve(204, "", (client, seen) -> {
            client.complete(report);
            assertEquals("/internal/worker/v1/submissions/" + report.submissionId() + "/attempts/" + report.attemptId() + "/result",
                    seen.get().path());
            String body = seen.get().body();
            assertTrue(body.contains("\"verdict\":\"WRONG_ANSWER\""));
            assertTrue(body.contains("\"totalScore\":\"0\""));
            assertTrue(body.contains("\"passedTests\":0"));
            assertTrue(body.contains("\"totalTests\":1"));
            assertTrue(body.contains("\"testCaseId\":\"" + test + "\""));
            assertTrue(body.contains("\"actualOutput\":\"6\\n\""));
        });
        serve(409, "", (client, seen) -> assertThrows(StaleAttemptException.class, () -> client.complete(report)));
        serve(422, "", (client, seen) -> assertThrows(PermanentApiException.class, () -> client.complete(report)));
        serve(500, "", (client, seen) -> {
            ApiException failure = assertThrows(ApiException.class, () -> client.complete(report));
            assertTrue(!(failure instanceof PermanentApiException) && !(failure instanceof StaleAttemptException));
        });
    }

    @Test
    void unreachableApiIsATransientFailure() {
        JudgeApiClient client = new HttpJudgeApiClient(Fixtures.properties("docker", "http://127.0.0.1:1", List.of("JAVA")));
        ApiException failure = assertThrows(ApiException.class,
                () -> client.begin(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        assertTrue(!(failure instanceof PermanentApiException));
    }
}
