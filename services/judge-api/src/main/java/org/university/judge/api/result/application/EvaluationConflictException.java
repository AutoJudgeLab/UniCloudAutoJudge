package org.university.judge.api.result.application;

/** A stale attempt, conflicting replay, or invalid report. No evaluator transport is exposed yet. */
public class EvaluationConflictException extends RuntimeException {
    public EvaluationConflictException(String message) { super(message); }
}

