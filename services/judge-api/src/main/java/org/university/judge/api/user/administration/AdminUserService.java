package org.university.judge.api.user.administration;

import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.university.judge.api.security.CurrentUser;
import org.university.judge.api.user.Role;
import org.university.judge.api.user.User;
import org.university.judge.api.user.UserRepository;
import org.university.judge.api.user.UserResponse;

import static org.university.judge.api.user.administration.UserManagementException.Reason.*;

@Service
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserService {
    private final UserRepository users;
    private final CurrentUser currentUser;
    private final JdbcTemplate jdbc;

    public AdminUserService(UserRepository users, CurrentUser currentUser, JdbcTemplate jdbc) {
        this.users = users;
        this.currentUser = currentUser;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public UserPageResponse list(UserSearchRequest request) {
        String[] sorting = request.sort().split(",");
        Sort sort = Sort.by(Sort.Direction.fromString(sorting[1]), sorting[0]);
        if (!sorting[0].equals("id")) sort = sort.and(Sort.by("id"));
        Specification<User> filter = (root, query, builder) -> {
            var predicates = new ArrayList<Predicate>();
            if (request.username() != null && !request.username().isBlank()) {
                predicates.add(builder.like(builder.lower(root.get("username")), containsLiteral(request.username()), '\\'));
            }
            if (request.email() != null && !request.email().isBlank()) {
                predicates.add(builder.like(builder.lower(root.get("email")), containsLiteral(request.email()), '\\'));
            }
            if (request.role() != null) predicates.add(builder.equal(root.get("role"), request.role()));
            if (request.enabled() != null) predicates.add(builder.equal(root.get("enabled"), request.enabled()));
            return builder.and(predicates.toArray(Predicate[]::new));
        };
        return UserPageResponse.from(users.findAll(filter, PageRequest.of(request.page(), request.size(), sort)));
    }

    @Transactional(readOnly = true)
    public UserResponse get(UUID userId) {
        return UserResponse.from(users.findById(userId).orElseThrow(() -> new UserManagementException(USER_NOT_FOUND)));
    }

    @Transactional
    public UserResponse changeRole(UUID userId, Role role) {
        User actor = lockAndVerifyAdministrator();
        if (actor.getId().equals(userId)) throw new AccessDeniedException("Users cannot change their own role");
        if (role == null || role == Role.ADMIN) throw new UserManagementException(INVALID_ROLE_CHANGE);
        User target = lockTarget(userId);
        if (target.isExternal()) throw new AccessDeniedException("Portal roles are managed by the portal");
        if (target.getRole() == Role.ADMIN) throw new UserManagementException(ADMIN_ROLE_PROTECTED);
        target.changeRole(role);
        users.flush();
        return UserResponse.from(target);
    }

    @Transactional
    public UserResponse changeStatus(UUID userId, boolean enabled) {
        lockAndVerifyAdministrator();
        User target = lockTarget(userId);
        if (!enabled && !target.isExternal() && target.isEnabled() && target.getRole() == Role.ADMIN
                && users.countByRoleAndEnabledTrue(Role.ADMIN) <= 1) {
            throw new UserManagementException(LAST_ACTIVE_ADMIN);
        }
        target.setEnabled(enabled);
        users.flush();
        return UserResponse.from(target);
    }

    private User lockAndVerifyAdministrator() {
        // Acquire before row locks, matching bootstrap and the database's admin guard.
        // Serialize infrequent administrative writes; reads and logins do not take this lock.
        jdbc.execute("SELECT pg_advisory_xact_lock(731024001)");
        var identity = currentUser.require();
        User actor = users.findByIdForUpdate(identity.id()).orElseThrow(() -> new BadCredentialsException("Authentication is required"));
        if (!actor.isEnabled() || actor.getTokenVersion() != identity.authenticationVersion()) {
            throw new BadCredentialsException("Authentication is required");
        }
        if (actor.getRole() != Role.ADMIN) throw new AccessDeniedException("Administrator privileges are required");
        return actor;
    }

    private User lockTarget(UUID userId) {
        return users.findByIdForUpdate(userId).orElseThrow(() -> new UserManagementException(USER_NOT_FOUND));
    }

    private static String containsLiteral(String value) {
        return "%" + value.strip().toLowerCase(Locale.ROOT).replace("\\", "\\\\")
                .replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
