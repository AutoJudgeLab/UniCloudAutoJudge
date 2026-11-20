package org.university.judge.api.result.application;

/** Invalid report data, distinguished from attempt/replay conflicts for the worker transport. */
public class EvaluationValidationException extends EvaluationConflictException {
    public EvaluationValidationException(String message) { super(message); }
}
