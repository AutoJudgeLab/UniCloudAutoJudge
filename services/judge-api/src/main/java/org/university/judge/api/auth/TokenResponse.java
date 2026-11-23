package org.university.judge.api.auth;

import io.swagger.v3.oas.annotations.media.Schema;

public record TokenResponse(String accessToken, @Schema(example = "Bearer") String tokenType,
                            @Schema(description = "Access token lifetime in seconds", example = "900") long expiresIn) {
    @Override
    public String toString() {
        return "TokenResponse[token redacted]";
    }
}
