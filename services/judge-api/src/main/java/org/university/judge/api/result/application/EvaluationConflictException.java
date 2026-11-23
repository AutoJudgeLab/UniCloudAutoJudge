package org.university.judge.api.result.application;

/** A stale attempt or conflicting replay; validation failures use the more specific subclass. */
public class EvaluationConflictException extends RuntimeException {
    public EvaluationConflictException(String message) { super(message); }
}

