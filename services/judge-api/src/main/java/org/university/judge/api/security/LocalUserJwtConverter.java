package org.university.judge.api.security;

import java.util.List;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.university.judge.api.user.User;
import org.university.judge.api.user.UserRepository;

@Component
public class LocalUserJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    private final UserRepository users;

    public LocalUserJwtConverter(UserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UUID id;
        try {
            id = UUID.fromString(jwt.getSubject());
            if (!id.toString().equals(jwt.getSubject())) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException | NullPointerException ignored) {
            throw invalidToken();
        }
        User user = users.findById(id).filter(User::isEnabled).orElseThrow(LocalUserJwtConverter::invalidToken);
        Object version = jwt.getClaim("token_version");
        if (!(version instanceof Long || version instanceof Integer)
                || ((Number) version).longValue() != user.getTokenVersion()) {
            throw invalidToken();
        }
        // Local membership is authoritative: role changes and disabling take effect
        // immediately, even if an older access token still has time remaining.
        return new PreAuthenticatedAuthenticationToken(new AuthenticatedUser(user.getId(), user.getRole(), user.getTokenVersion()), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
    }

    private static OAuth2AuthenticationException invalidToken() {
        return new OAuth2AuthenticationException(new OAuth2Error("invalid_token"));
    }
}
