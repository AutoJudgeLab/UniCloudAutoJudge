package org.university.judge.api.user.administration;

import java.util.UUID;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.university.judge.api.user.UserResponse;

@RestController
@RequestMapping("/api/admin/users")
@Tag(name = "Administrator user management")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "401", description = "Missing, invalid or revoked authentication",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "403", description = "Administrator privileges required or own role change prohibited",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "400", description = "Invalid request, filter, sort or role",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class AdminUserController {
    private final AdminUserService users;

    public AdminUserController(AdminUserService users) {
        this.users = users;
    }

    @GetMapping
    @Operation(summary = "List users", description = "ADMIN only. Zero-based pagination, case-insensitive substring searches, AND-combined filters, and allowlisted sorting.")
    @ApiResponse(responseCode = "200", description = "User management response",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = UserPageResponse.class)))
    public UserPageResponse list(@Valid @ModelAttribute @ParameterObject UserSearchRequest request) {
        return users.list(request);
    }

    @GetMapping("/{userId}")
    @Operation(summary = "Get a user", description = "ADMIN only. Returns public profile fields without password or token metadata.")
    @ApiResponse(responseCode = "404", description = "User not found",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "200", description = "User management response",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = UserResponse.class)))
    public UserResponse get(@PathVariable UUID userId) {
        return users.get(userId);
    }

    @PatchMapping("/{userId}/role")
    @Operation(summary = "Change a student's or teacher's role", description = "ADMIN only. Allows STUDENT and TEACHER; cannot alter an administrator's role or the caller's own role. Repeating the current allowed role is a no-op.")
    @ApiResponse(responseCode = "404", description = "User not found",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Administrator role protected or concurrent update; retry a concurrency conflict",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public UserResponse changeRole(@PathVariable UUID userId, @Valid @RequestBody ChangeUserRoleRequest request) {
        return users.changeRole(userId, request.role());
    }

    @PatchMapping("/{userId}/status")
    @Operation(summary = "Enable or disable an account", description = "ADMIN only. Actual status changes revoke all previously issued tokens permanently, including after re-enabling. The last active administrator cannot be disabled.")
    @ApiResponse(responseCode = "404", description = "User not found",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Last active administrator protected or concurrent update; retry a concurrency conflict",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "200", description = "User management response",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = UserResponse.class)))
    public UserResponse changeStatus(@PathVariable UUID userId, @Valid @RequestBody ChangeUserStatusRequest request) {
        return users.changeStatus(userId, request.enabled());
    }
}
