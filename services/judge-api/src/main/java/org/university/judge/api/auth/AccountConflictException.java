package org.university.judge.api.auth;

public class AccountConflictException extends RuntimeException {
    public AccountConflictException() {
        super("Username or email is already registered");
    }
}
