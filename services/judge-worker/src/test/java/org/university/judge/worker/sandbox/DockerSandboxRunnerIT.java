package org.university.judge.worker.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.university.judge.worker.Fixtures;
import org.university.judge.worker.config.WorkerProperties;
import org.university.judge.worker.grading.Grader;
import org.university.judge.worker.grading.LanguageProfiles;
import org.university.judge.worker.grading.Verdict;
import org.university.judge.worker.model.EvaluationReport;
import org.university.judge.worker.model.JudgeJob;

/**
 * Real Java submissions, including hostile ones, through the real Docker sandbox.
 * Needs a running Docker daemon and the image pulled beforehand: docker pull eclipse-temurin:21-jdk
 * Run with: mvn -pl services/judge-worker -am verify -Pintegration-tests
 */
/* A stuck sandbox must fail the build after two minutes, never hang it. */
@Timeout(value = 2, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class DockerSandboxRunnerIT {

    private static Grader grader;

    @BeforeAll
    static void requireDocker() throws Exception {
        Process info = new ProcessBuilder("docker", "image", "inspect", "eclipse-temurin:21-jdk").redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        boolean answered = info.waitFor(30, TimeUnit.SECONDS);
        if (!answered) info.destroyForcibly();
        Assumptions.assumeTrue(answered && info.exitValue() == 0, "Docker with eclipse-temurin:21-jdk is required");
        WorkerProperties properties = Fixtures.properties("docker", "http://localhost:1", List.of("JAVA"));
        grader = new Grader(new DockerSandboxRunner(properties), new LanguageProfiles(properties), properties);
    }

    private static EvaluationReport grade(String body) {
        String source = "import java.util.*;\npublic class Main {\n  public static void main(String[] args) throws Exception {\n"
                + body + "\n  }\n}\n";
        JudgeJob job = Fixtures.job("JAVA", "Main.java", source, Fixtures.test(0, "2 3\n", "5\n", "100"));
        return grader.grade(job);
    }

    @Test
    void correctSolutionIsAccepted() {
        EvaluationReport report = grade("Scanner in = new Scanner(System.in); System.out.println(in.nextInt() + in.nextInt());");
        assertEquals(Verdict.ACCEPTED, report.verdict());
        assertEquals(0, new BigDecimal("100").compareTo(report.totalScore()));
    }

    @Test
    void wrongOutputIsAWrongAnswer() {
        assertEquals(Verdict.WRONG_ANSWER, grade("System.out.println(6);").verdict());
    }

    @Test
    void syntaxErrorIsACompilationError() {
        assertEquals(Verdict.COMPILATION_ERROR, grade("int x = ;").verdict());
    }

    @Test
    void uncaughtExceptionIsARuntimeError() {
        assertEquals(Verdict.RUNTIME_ERROR, grade("int[] a = new int[1]; System.out.println(a[5]);").verdict());
    }

    @Test
    void infiniteLoopIsStoppedAtTheTimeLimit() {
        assertEquals(Verdict.TIME_LIMIT_EXCEEDED, grade("while (true) { }").verdict());
    }

    @Test
    void heapExhaustionIsAMemoryVerdict() {
        assertEquals(Verdict.MEMORY_LIMIT_EXCEEDED,
                grade("List<long[]> hog = new ArrayList<>(); while (true) hog.add(new long[1_000_000]);").verdict());
    }

    @Test
    void endlessOutputIsCutOff() {
        assertEquals(Verdict.RUNTIME_ERROR, grade("while (true) System.out.println(\"a\".repeat(1000));").verdict());
    }

    @Test
    void networkIsUnreachable() {
        EvaluationReport report = grade("""
                try { new java.net.Socket().connect(new java.net.InetSocketAddress("1.1.1.1", 80), 1500); System.out.println("CONNECTED"); }
                catch (java.io.IOException blocked) { System.out.println(5); }""");
        assertFalse(report.tests().get(0).actualOutput().contains("CONNECTED"));
        assertEquals(Verdict.ACCEPTED, report.verdict());
    }

    @Test
    void fileSystemIsReadOnlyAndTheSourceCannotBeReplaced() {
        EvaluationReport report = grade("""
                boolean wrote = true;
                try { java.nio.file.Files.writeString(java.nio.file.Path.of("/work/Main.java"), "x"); } catch (Exception e) { wrote = false; }
                try { java.nio.file.Files.writeString(java.nio.file.Path.of("/etc/judge"), "x"); wrote = true; } catch (Exception e) { }
                System.out.println(wrote ? "WROTE" : "5");""");
        assertEquals(Verdict.ACCEPTED, report.verdict());
    }

    @Test
    void processAndThreadFloodsStayInsideTheLimits() {
        EvaluationReport report = grade("while (true) new Thread(() -> { try { Thread.sleep(60_000); } catch (Exception e) { } }).start();");
        assertNotEquals(Verdict.ACCEPTED, report.verdict());
        assertNotEquals(Verdict.INTERNAL_ERROR, report.verdict());
        assertTrue(report.tests().size() == 1);
    }
}
