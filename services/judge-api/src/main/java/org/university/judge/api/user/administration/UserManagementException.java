package org.university.judge.api.user.administration;

public class UserManagementException extends RuntimeException {
    public enum Reason {
        USER_NOT_FOUND("User not found"),
        INVALID_ROLE_CHANGE("Only STUDENT and TEACHER can be assigned through this endpoint"),
        ADMIN_ROLE_PROTECTED("Administrator roles require a separate explicitly authorized mechanism"),
        LAST_ACTIVE_ADMIN("The last active administrator cannot be disabled");

        private final String message;

        Reason(String message) { this.message = message; }
    }

    private final Reason reason;

    public UserManagementException(Reason reason) {
        super(reason.message);
        this.reason = reason;
    }

    public Reason getReason() { return reason; }
}
