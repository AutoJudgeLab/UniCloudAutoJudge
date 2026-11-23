package org.university.judge.worker.model;

import java.math.BigDecimal;
import java.util.UUID;
import org.university.judge.worker.grading.Verdict;

/** Mirrors EvaluationCompletion.TestOutcome in judge-api. */
public record TestOutcome(UUID testCaseId, Verdict status, BigDecimal awardedPoints, Long executionTimeMs, String actualOutput) { }
