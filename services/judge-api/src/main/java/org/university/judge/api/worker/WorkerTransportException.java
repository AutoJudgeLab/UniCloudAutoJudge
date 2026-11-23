package org.university.judge.api.worker;

import org.springframework.http.HttpStatus;

public class WorkerTransportException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public WorkerTransportException(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
}
