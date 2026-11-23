package org.university.judge.worker;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.university.judge.worker.grading.Verdict;
import org.university.judge.worker.model.EvaluationReport;
import org.university.judge.worker.model.TestCase;
import org.university.judge.worker.model.TestOutcome;

/**
 * The validation EvaluationCompletionService.validate applies in judge-api, restated for tests, so a
 * report the worker builds is checked against the rules that decide whether the API stores it.
 * Keep it in step with that method.
 */
public final class ApiReportRules {

    private ApiReportRules() { }

    /** @return null when the API would accept the report, otherwise the API's rejection message */
    public static String violation(EvaluationReport report, List<TestCase> configured) {
        if (report.verdict() == null || report.tests() == null || !score(report.totalScore()) || !duration(report.executionTimeMs())) {
            return "Invalid summary";
        }
        if (report.totalTests() != configured.size() || configured.isEmpty()) return "Test count must match the sealed revision";
        Map<UUID, TestCase> remaining = new HashMap<>();
        configured.forEach(test -> remaining.put(test.testCaseId(), test));
        BigDecimal sum = BigDecimal.ZERO;
        int passed = 0;
        boolean internal = false;
        for (TestOutcome test : report.tests()) {
            TestCase definition = remaining.remove(test.testCaseId());
            if (definition == null) return "Duplicate test or test outside the submission revision";
            if (test.status() == null || !score(test.awardedPoints()) || !duration(test.executionTimeMs())) return "Invalid test outcome";
            if (test.actualOutput() != null && (test.actualOutput().indexOf(0) >= 0
                    || test.actualOutput().getBytes(StandardCharsets.UTF_8).length > 65536)) {
                return "Output exceeds the 64 KiB limit or contains NUL";
            }
            boolean accepted = test.status() == Verdict.ACCEPTED;
            if (test.awardedPoints().compareTo(accepted ? definition.points() : BigDecimal.ZERO) != 0) {
                return "Accepted tests earn their configured points; other outcomes earn zero";
            }
            if (accepted) passed++;
            if (test.status() == Verdict.INTERNAL_ERROR) internal = true;
            sum = sum.add(test.awardedPoints());
        }
        if (report.totalScore().compareTo(sum) != 0 || report.passedTests() != passed) return "Summary does not match reported tests";
        boolean compile = report.verdict() == Verdict.COMPILATION_ERROR;
        boolean failed = report.verdict() == Verdict.INTERNAL_ERROR;
        if (internal && !failed) return "Internal test errors require an internal-error summary";
        if (compile) {
            if (!report.tests().isEmpty() || report.totalScore().signum() != 0) return "Compilation failure cannot contain executed tests";
        } else if (!failed && !remaining.isEmpty()) {
            return "Every configured test must be reported";
        }
        if (report.verdict() == Verdict.ACCEPTED) {
            if (passed != configured.size()) return "Accepted summary requires all tests to pass";
        } else if (!failed && !compile) {
            if (passed >= configured.size() || report.tests().stream().noneMatch(test -> test.status() == report.verdict())) {
                return "Verdict must describe a reported failure";
            }
        }
        return null;
    }

    private static boolean score(BigDecimal value) {
        return value != null && value.signum() >= 0 && value.stripTrailingZeros().scale() <= 4
                && value.compareTo(new BigDecimal("1000000000000000")) < 0;
    }

    private static boolean duration(Long value) {
        return value == null || value >= 0;
    }
}
