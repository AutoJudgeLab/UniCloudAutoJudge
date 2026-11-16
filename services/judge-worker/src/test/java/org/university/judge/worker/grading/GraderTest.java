package org.university.judge.worker.grading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.university.judge.worker.ApiReportRules;
import org.university.judge.worker.Fixtures;
import org.university.judge.worker.config.WorkerProperties;
import org.university.judge.worker.model.EvaluationReport;
import org.university.judge.worker.model.JudgeJob;
import org.university.judge.worker.sandbox.RunRequest;
import org.university.judge.worker.sandbox.RunResult;
import org.university.judge.worker.sandbox.RunResult.Outcome;
import org.university.judge.worker.sandbox.SandboxException;

/** Grading logic with a scripted sandbox; no Docker involved. Every report is checked against the API's rules. */
class GraderTest {

    private static final String SOURCE = "public class Main { public static void main(String[] a) { } }";
    private final List<RunRequest> requests = new ArrayList<>();

    private Grader grader(Function<RunRequest, RunResult> script) {
        WorkerProperties properties = Fixtures.properties();
        return new Grader(request -> {
            requests.add(request);
            return script.apply(request);
        }, new LanguageProfiles(properties), properties);
    }

    private static boolean compiling(RunRequest request) {
        return request.command().get(0).equals("javac");
    }

    private static RunResult ok(String stdout) {
        return new RunResult(Outcome.COMPLETED, 0, stdout, "", 120);
    }

    private static EvaluationReport accepted(EvaluationReport report, JudgeJob job) {
        assertNull(ApiReportRules.violation(report, job.tests()));
        return report;
    }

    @Test
    void allTestsPassing() {
        JudgeJob job = Fixtures.job("JAVA", "Main.java", SOURCE, Fixtures.test(0, "2 3\n", "5\n", "40"), Fixtures.test(1, "1 1\n", "2\n", "60"));
        EvaluationReport report = accepted(grader(r -> compiling(r) ? ok("") : ok(r.stdin().startsWith("2") ? "5" : "2\n")).grade(job), job);
        assertEquals(Verdict.ACCEPTED, report.verdict());
        assertEquals(0, new BigDecimal("100").compareTo(report.totalScore()));
        assertEquals(2, report.passedTests());
        assertEquals(2, report.totalTests());
        assertEquals(120L, report.executionTimeMs());
    }

    @Test
    void partialScoreAndVerdictOfTheFirstFailure() {
        JudgeJob job = Fixtures.job("JAVA", "Main.java", SOURCE, Fixtures.test(0, "a", "1", "2.5"), Fixtures.test(1, "b", "2", "2.5"),
                Fixtures.test(2, "c", "3", "5"));
        EvaluationReport report = accepted(grader(r -> {
            if (compiling(r)) return ok("");
            return switch (r.stdin()) {
                case "a" -> ok("1");
                case "b" -> ok("wrong");
                default -> new RunResult(Outcome.TIME_LIMIT, -1, "", "", 5000);
            };
        }).grade(job), job);
        assertEquals(Verdict.WRONG_ANSWER, report.verdict());
        assertEquals(0, new BigDecimal("2.5").compareTo(report.totalScore()));
        assertEquals(1, report.passedTests());
        assertEquals(3, report.tests().size());
        assertEquals(Verdict.TIME_LIMIT_EXCEEDED, report.tests().get(2).status());
    }

    @Test
    void testsRunInExecutionOrderWithTheirInputOnStdin() {
        JudgeJob job = Fixtures.job("JAVA", "Main.java", SOURCE, Fixtures.test(2, "third", "x", "1"), Fixtures.test(0, "first", "x", "1"),
                Fixtures.test(1, "second", "x", "1"));
        accepted(grader(r -> ok("x")).grade(job), job);
        assertEquals(List.of("first", "second", "third"), requests.stream().filter(r -> !compiling(r)).map(RunRequest::stdin).toList());
    }

    @Test
    void compilationErrorReportsNoTests() {
        JudgeJob job = Fixtures.job("JAVA", "Main.java", "class {", Fixtures.test(0, "", "1", "10"));
        EvaluationReport report = accepted(grader(r -> new RunResult(Outcome.COMPLETED, 1, "", "Main.java:1: error", 800)).grade(job), job);
        assertEquals(Verdict.COMPILATION_ERROR, report.verdict());
        assertTrue(report.tests().isEmpty());
        assertEquals(1, requests.size());
    }

    @Test
    void runtimeMemoryAndOutputFailuresMapToApiVerdicts() {
        JudgeJob job = Fixtures.job("JAVA", "Main.java", SOURCE, Fixtures.test(0, "crash", "1", "1"), Fixtures.test(1, "heap", "1", "1"),
                Fixtures.test(2, "oom-kill", "1", "1"), Fixtures.test(3, "flood", "1", "1"));
        EvaluationReport report = accepted(grader(r -> {
            if (compiling(r)) return ok("");
            return switch (r.stdin()) {
                case "crash" -> new RunResult(Outcome.COMPLETED, 1, "", "Exception in thread \"main\" java.lang.ArithmeticException", 90);
                case "heap" -> new RunResult(Outcome.COMPLETED, 1, "", "java.lang.OutOfMemoryError: Java heap space", 90);
                case "oom-kill" -> new RunResult(Outcome.MEMORY_LIMIT, 137, "", "", 90);
                default -> new RunResult(Outcome.OUTPUT_LIMIT, -1, "aaaa", "", 90);
            };
        }).grade(job), job);
        assertEquals(List.of(Verdict.RUNTIME_ERROR, Verdict.MEMORY_LIMIT_EXCEEDED, Verdict.MEMORY_LIMIT_EXCEEDED, Verdict.RUNTIME_ERROR),
                report.tests().stream().map(t -> t.status()).toList());
        assertEquals(Verdict.RUNTIME_ERROR, report.verdict());
    }

    @Test
    void javaGetsHeapEqualToTheLimitAndTheContainerSomeHeadroom() {
        JudgeJob job = Fixtures.job("JAVA", "Main.java", SOURCE, Fixtures.test(0, "", "x", "1"));
        grader(r -> ok("x")).grade(job);
        RunRequest compile = requests.get(0);
        RunRequest run = requests.get(1);
        assertTrue(compile.workDirWritable());
        assertTrue(!run.workDirWritable());
        assertTrue(run.command().contains("-Xmx256m"));
        assertEquals(256 + 160, run.memoryMb());
        assertEquals(2000, run.timeLimitMillis());
    }

    @Test
    void storedOutputIsSanitized() {
        JudgeJob job = Fixtures.job("JAVA", "Main.java", SOURCE, Fixtures.test(0, "", "1", "1"));
        EvaluationReport report = accepted(grader(r -> compiling(r) ? ok("") : ok("x\u0000".repeat(60_000))).grade(job), job);
        assertEquals(16_384, report.tests().get(0).actualOutput().length());
    }

    @Test
    void languageWithoutEvaluatorEndsAsInternalError() {
        JudgeJob job = Fixtures.job("RUST", "main.rs", "fn main() {}", Fixtures.test(0, "", "1", "10"));
        EvaluationReport report = accepted(grader(r -> ok("")).grade(job), job);
        assertEquals(Verdict.INTERNAL_ERROR, report.verdict());
        assertTrue(requests.isEmpty());
    }

    @Test
    void sandboxFailureIsNeverBlamedOnTheStudent() {
        JudgeJob job = Fixtures.job("JAVA", "Main.java", SOURCE, Fixtures.test(0, "", "1", "10"));
        assertThrows(SandboxException.class, () -> grader(r -> RunResult.sandboxError("Cannot connect to the Docker daemon")).grade(job));
        requests.clear();
        assertThrows(SandboxException.class,
                () -> grader(r -> compiling(r) ? ok("") : new RunResult(Outcome.SANDBOX_ERROR, 125, "", "Unable to find image", 5)).grade(job));
    }
}
