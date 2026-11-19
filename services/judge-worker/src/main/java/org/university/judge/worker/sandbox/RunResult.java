package org.university.judge.worker.sandbox;

public record RunResult(Outcome outcome, int exitCode, String stdout, String stderr, long elapsedMillis) {

    public enum Outcome {
        /** The program ran to completion; exitCode says whether it succeeded. */
        COMPLETED,
        TIME_LIMIT,
        MEMORY_LIMIT,
        OUTPUT_LIMIT,
        /** The sandbox itself failed (Docker missing, image not pulled...). Never the student's fault. */
        SANDBOX_ERROR
    }

    public static RunResult sandboxError(String message) {
        return new RunResult(Outcome.SANDBOX_ERROR, -1, "", message, 0);
    }
}
