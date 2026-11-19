package org.university.judge.worker.grading;

/** Same names as org.university.judge.api.result.Verdict; the API stores them verbatim. */
public enum Verdict {
    ACCEPTED,
    WRONG_ANSWER,
    COMPILATION_ERROR,
    RUNTIME_ERROR,
    TIME_LIMIT_EXCEEDED,
    MEMORY_LIMIT_EXCEEDED,
    INTERNAL_ERROR
}
