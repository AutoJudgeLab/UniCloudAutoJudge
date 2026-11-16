package org.university.judge.worker.client;

/** The API no longer recognizes this attempt as the current one. */
public class StaleAttemptException extends ApiException {
    public StaleAttemptException(String message) {
        super(message);
    }
}
