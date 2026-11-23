package org.university.judge.api.security.portal;

import java.util.Collection;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.transaction.annotation.Transactional;
import org.university.judge.api.security.AuthenticatedUser;
import org.university.judge.api.user.Role;
import org.university.judge.api.user.User;
import org.university.judge.api.user.UserRepository;

public class PortalIdentityService {
    private final PortalSettings settings;
    private final UserRepository users;
    private final JdbcTemplate jdbc;

    public PortalIdentityService(PortalSettings settings, UserRepository users, JdbcTemplate jdbc) {
        this.settings = settings; this.users = users; this.jdbc = jdbc;
    }

    @Transactional
    public AuthenticatedUser resolve(String subject, Map<String, Object> claims) {
        if (subject == null || subject.isBlank() || subject.length() > 255 || !subject.equals(claims.get("sub"))) throw invalid();
        if (!(claims.get("permissions") instanceof Collection<?> permissions)
                || permissions.stream().anyMatch(p -> !(p instanceof String))) throw invalid();
        Role role = switch (String.valueOf(claims.get("role"))) {
            case "STUDENT" -> permissions.contains("STUDENT_PROFILE_READ_OWN") && permissions.contains("COURSE_OFFERING_READ")
                    ? Role.STUDENT : null;
            case "TEACHER" -> permissions.contains("COURSE_OFFERING_READ") ? Role.TEACHER : null;
            case "ADMIN" -> settings.adminSubjects().contains(subject) ? Role.ADMIN : null;
            // INSPECTOR has no Judge capability mapping; never interpret it as a teacher/admin.
            default -> null;
        };
        if (role == null) throw new OAuth2AuthenticationException(new OAuth2Error("insufficient_scope"));
        String email = claims.get("email") instanceof String value ? value : null;
        if (email != null && (email.isBlank() || email.length() > 254 || !email.contains("@"))) throw invalid();

        // External users never participate in the local last-admin guard. Serialize only this
        // identity (including first login), then lock its row against administrative disabling.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 731024002))",
                rs -> { }, settings.issuer() + "\n" + subject);
        User user = users.findExternalForUpdate(settings.issuer(), subject).orElse(null);
        if (user == null) {
            user = users.saveAndFlush(User.portal(settings.issuer(), subject, email, role));
        } else {
            if (!user.isEnabled()) throw invalid();
            if (!java.util.Objects.equals(email, user.getEmail()) || role != user.getRole()) {
                user.synchronizePortal(email, role);
                users.flush();
            }
        }
        return new AuthenticatedUser(user.getId(), role, user.getTokenVersion());
    }

    static OAuth2AuthenticationException invalid() {
        return new OAuth2AuthenticationException(new OAuth2Error("invalid_token"));
    }
}
