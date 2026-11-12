package org.university.judge.api.auth;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.university.judge.api.user.Role;
import org.university.judge.api.user.User;
import org.university.judge.api.user.UserRepository;
import org.university.judge.api.user.UserResponse;

@Service
public class AuthenticationService {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final AccessTokenService tokens;
    private final String dummyPasswordHash;

    public AuthenticationService(UserRepository users, PasswordEncoder passwords, AccessTokenService tokens) {
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
        this.dummyPasswordHash = passwords.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        if (users.existsByUsernameIgnoreCase(request.username()) || users.existsByEmailIgnoreCase(request.email())) {
            throw new AccountConflictException();
        }
        try {
            return UserResponse.from(users.saveAndFlush(new User(request.username(), request.email(),
                    passwords.encode(request.password()), Role.STUDENT)));
        } catch (DataIntegrityViolationException ignored) {
            // The unique indexes also cover registration races after the checks above.
            throw new AccountConflictException();
        }
    }

    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        User user = (request.identifier().contains("@") ? users.findByEmailIgnoreCase(request.identifier())
                : users.findByUsernameIgnoreCase(request.identifier())).orElse(null);
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BadCredentialsException("Invalid credentials");
        }
        boolean matches = passwords.matches(request.password(), user == null ? dummyPasswordHash : user.getPasswordHash());
        if (!matches || user == null || !user.isEnabled()) {
            throw new BadCredentialsException("Invalid credentials");
        }
        return tokens.issue(user);
    }
}
