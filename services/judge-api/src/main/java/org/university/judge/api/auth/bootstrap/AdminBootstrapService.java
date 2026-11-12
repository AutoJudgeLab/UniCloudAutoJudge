package org.university.judge.api.auth.bootstrap;

import jakarta.validation.Validator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.university.judge.api.auth.RegisterRequest;
import org.university.judge.api.user.Role;
import org.university.judge.api.user.User;
import org.university.judge.api.user.UserRepository;

@Service
public class AdminBootstrapService {
    private final AdminBootstrapSettings settings;
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final Validator validator;
    private final JdbcTemplate jdbc;

    public AdminBootstrapService(AdminBootstrapSettings settings, UserRepository users,
            PasswordEncoder passwords, Validator validator, JdbcTemplate jdbc) {
        this.settings = settings;
        this.users = users;
        this.passwords = passwords;
        this.validator = validator;
        this.jdbc = jdbc;
    }

    @Transactional
    public void provisionIfEnabled() {
        if (!settings.isEnabled()) {
            return;
        }
        jdbc.execute("SELECT pg_advisory_xact_lock(731024001)");
        if (users.existsByRole(Role.ADMIN)) {
            return;
        }
        RegisterRequest request = new RegisterRequest(settings.getUsername(), settings.getEmail(), settings.getPassword());
        if (!validator.validate(request).isEmpty()) {
            throw new IllegalStateException("Administrator bootstrap requires a valid username, email and strong password");
        }
        if (users.existsByUsernameIgnoreCase(request.username()) || users.existsByEmailIgnoreCase(request.email())) {
            throw new IllegalStateException("Administrator bootstrap identity is already in use; existing accounts are never promoted");
        }
        users.saveAndFlush(new User(request.username(), request.email(), passwords.encode(request.password()), Role.ADMIN));
    }
}
