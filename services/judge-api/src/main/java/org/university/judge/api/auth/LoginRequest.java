package org.university.judge.api.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Size(max = 254) @Schema(description = "Username or email, case-insensitive") String identifier,
        @NotBlank @Size(max = 72) @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) String password) {
    public LoginRequest {
        identifier = Identifiers.normalize(identifier);
    }

    @Override
    public String toString() {
        return "LoginRequest[credentials redacted]";
    }
}
