package org.university.judge.worker.grading;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.university.judge.worker.config.WorkerProperties;
import org.university.judge.worker.model.EvaluationReport;
import org.university.judge.worker.model.JudgeJob;
import org.university.judge.worker.model.TestCase;
import org.university.judge.worker.model.TestOutcome;
import org.university.judge.worker.sandbox.RunRequest;
import org.university.judge.worker.sandbox.RunResult;
import org.university.judge.worker.sandbox.SandboxException;
import org.university.judge.worker.sandbox.SandboxRunner;

/**
 * Evaluates one submission: writes the source to a private directory, compiles it, runs every test in
 * the sandbox and builds a report that satisfies EvaluationCompletionService's validation rules.
 *
 * <p>Outcomes that are the student's doing (wrong answer, time limit, compilation error...) come back as a
 * report. A sandbox that cannot run at all throws {@link SandboxException}: the message stays unacknowledged
 * and is retried, so infrastructure trouble never turns into a zero score.
 */
@Component
public class Grader {

    private static final Logger LOG = LoggerFactory.getLogger(Grader.class);

    private final SandboxRunner sandbox;
    private final LanguageProfiles languages;
    private final WorkerProperties.Limits limits;

    public Grader(SandboxRunner sandbox, LanguageProfiles languages, WorkerProperties properties) {
        this.sandbox = sandbox;
        this.languages = languages;
        this.limits = properties.limits();
    }

    public EvaluationReport grade(JudgeJob job) {
        LanguageProfile language = languages.find(job.programmingLanguage()).orElse(null);
        if (language == null) {
            LOG.warn("Submission {} uses {}, which has no enabled evaluator", job.submissionId(), job.programmingLanguage());
            return EvaluationReport.internalError(job);
        }
        long timeLimit = job.timeLimitMs() != null ? job.timeLimitMs() : limits.timeLimit().toMillis();
        int memoryMb = job.memoryLimitMb() != null ? job.memoryLimitMb() : limits.memoryMb();
        LanguageProfile.Plan plan = language.plan(job, memoryMb);

        Path directory = null;
        try {
            directory = Files.createTempDirectory("judge-");
            sandbox.prepare(directory);
            Files.writeString(directory.resolve(plan.sourceFileName()), job.source() == null ? "" : job.source(),
                    StandardCharsets.UTF_8);

            if (!plan.compileCommand().isEmpty()) {
                RunResult compilation = sandbox.run(new RunRequest(language.image(), directory, true, plan.compileCommand(),
                        "", limits.compileTimeLimit().toMillis(), limits.compileMemoryMb()));
                if (compilation.outcome() == RunResult.Outcome.SANDBOX_ERROR || compilation.exitCode() == 126
                        || compilation.exitCode() == 127) {
                    throw new SandboxException("Compiler could not be started: " + firstLine(compilation.stderr()));
                }
                if (compilation.outcome() != RunResult.Outcome.COMPLETED || compilation.exitCode() != 0) {
                    return new EvaluationReport(job.submissionId(), job.attemptId(), Verdict.COMPILATION_ERROR,
                            BigDecimal.ZERO, 0, job.tests().size(), null, List.of());
                }
            }

            List<TestCase> ordered = job.tests().stream().sorted(Comparator.comparingInt(TestCase::executionOrder)).toList();
            List<TestOutcome> outcomes = new ArrayList<>();
            // The API requires an outcome for every configured test, so a failure does not stop the run.
            for (TestCase test : ordered) {
                outcomes.add(runTest(language, plan, directory, test, timeLimit, memoryMb));
            }
            return summarize(job, outcomes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            delete(directory);
        }
    }

    private TestOutcome runTest(LanguageProfile language, LanguageProfile.Plan plan, Path directory, TestCase test,
            long timeLimit, int memoryMb) {
        RunResult run = sandbox.run(new RunRequest(language.image(), directory, false, plan.runCommand(), test.inputData(),
                timeLimit, memoryMb + language.memoryOverheadMb()));
        Verdict verdict = switch (run.outcome()) {
            case SANDBOX_ERROR -> throw new SandboxException("Sandbox failed: " + firstLine(run.stderr()));
            case TIME_LIMIT -> Verdict.TIME_LIMIT_EXCEEDED;
            case MEMORY_LIMIT -> Verdict.MEMORY_LIMIT_EXCEEDED;
            // The API has no output-limit verdict; flooding stdout is reported as a runtime error.
            case OUTPUT_LIMIT -> Verdict.RUNTIME_ERROR;
            case COMPLETED -> {
                if (run.exitCode() != 0) {
                    yield language.ranOutOfMemory(run.stderr()) ? Verdict.MEMORY_LIMIT_EXCEEDED : Verdict.RUNTIME_ERROR;
                }
                yield OutputComparator.matches(test.expectedOutput(), run.stdout()) ? Verdict.ACCEPTED : Verdict.WRONG_ANSWER;
            }
        };
        BigDecimal points = verdict == Verdict.ACCEPTED ? test.points() : BigDecimal.ZERO;
        return new TestOutcome(test.testCaseId(), verdict, points, run.elapsedMillis(),
                OutputSanitizer.forStorage(run.stdout(), limits.storedOutputBytes()));
    }

    static EvaluationReport summarize(JudgeJob job, List<TestOutcome> outcomes) {
        BigDecimal total = BigDecimal.ZERO;
        int passed = 0;
        long slowest = 0;
        Verdict verdict = Verdict.ACCEPTED;
        for (TestOutcome outcome : outcomes) {
            total = total.add(outcome.awardedPoints());
            if (outcome.status() == Verdict.ACCEPTED) passed++;
            else if (verdict == Verdict.ACCEPTED) verdict = outcome.status();
            if (outcome.executionTimeMs() != null) slowest = Math.max(slowest, outcome.executionTimeMs());
        }
        return new EvaluationReport(job.submissionId(), job.attemptId(), verdict, total, passed, job.tests().size(),
                outcomes.isEmpty() ? null : slowest, outcomes);
    }

    private static String firstLine(String text) {
        if (text == null || text.isBlank()) return "(no diagnostics)";
        String line = text.strip().lines().findFirst().orElse("");
        return line.length() <= 300 ? line : line.substring(0, 300);
    }

    private static void delete(Path directory) {
        if (directory == null) return;
        try (Stream<Path> files = Files.walk(directory)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException | UncheckedIOException e) {
            LOG.warn("Could not remove work directory {}: {}", directory, e.getMessage());
        }
    }
}
