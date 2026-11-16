package org.university.judge.worker.client;

/** The API could not be reached or answered with a temporary failure. */
public class ApiException extends RuntimeException {
    public ApiException(String message) {
        super(message);
    }

    public ApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
