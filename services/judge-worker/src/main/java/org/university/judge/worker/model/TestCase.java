package org.university.judge.worker.model;

import java.math.BigDecimal;
import java.util.UUID;

/** One test of the submission's immutable grading revision, hidden tests included. */
public record TestCase(UUID testCaseId, String inputData, String expectedOutput, BigDecimal points, int executionOrder) { }
