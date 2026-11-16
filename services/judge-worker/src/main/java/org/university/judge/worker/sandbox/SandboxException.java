package org.university.judge.worker.sandbox;

/** The sandbox could not run at all. The evaluation is retried instead of being blamed on the student. */
public class SandboxException extends RuntimeException {
    public SandboxException(String message) {
        super(message);
    }
}
