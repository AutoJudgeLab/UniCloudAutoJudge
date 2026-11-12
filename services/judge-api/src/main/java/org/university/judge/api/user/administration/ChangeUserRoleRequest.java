package org.university.judge.api.user.administration;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import org.university.judge.api.user.Role;

public record ChangeUserRoleRequest(
        @NotNull @Schema(allowableValues = {"STUDENT", "TEACHER"}, description = "ADMIN cannot be assigned or removed here") Role role) {
}
