package org.university.judge.api.user;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(UUID id, String username, String email, Role role, boolean enabled, Instant createdAt) {
    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getEmail(), user.getRole(),
                user.isEnabled(), user.getCreatedAt());
    }
}
