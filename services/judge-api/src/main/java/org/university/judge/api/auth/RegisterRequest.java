package org.university.judge.api.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9_.-]{2,49}",
                message = "Use 3–50 letters, digits, dots, underscores or hyphens; start with a letter or digit")
        @Schema(description = "Case-insensitive username; 3–50 ASCII characters") String username,
        @NotBlank @Email @Size(max = 254) String email,
        @StrongPassword @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY, requiredMode = Schema.RequiredMode.REQUIRED,
                minLength = 12, maxLength = 72,
                description = "At least 12 characters and at most 72 UTF-8 bytes; uppercase, lowercase, digit and symbol; no whitespace")
        String password) {
    public RegisterRequest {
        username = Identifiers.normalize(username);
        email = Identifiers.normalize(email);
    }

    @Override
    public String toString() {
        return "RegisterRequest[credentials redacted]";
    }
}
