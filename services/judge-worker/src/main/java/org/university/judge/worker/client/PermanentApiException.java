package org.university.judge.worker.client;

/** The API refused the request in a way that retrying cannot fix. */
public class PermanentApiException extends ApiException {
    public PermanentApiException(String message) {
        super(message);
    }
}
