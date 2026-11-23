package org.university.judge.api.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.university.judge.api.security.JwtSettings;
import org.university.judge.api.user.User;

@Service
@ConditionalOnProperty(name = "security.authentication-mode", havingValue = "local", matchIfMissing = true)
public class AccessTokenService {
    private final JwtEncoder encoder;
    private final JwtSettings settings;

    public AccessTokenService(JwtEncoder encoder, JwtSettings settings) {
        this.encoder = encoder;
        this.settings = settings;
    }

    public TokenResponse issue(User user) {
        Instant issuedAt = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(settings.getIssuer())
                .audience(List.of(settings.getAudience())).subject(user.getId().toString())
                .issuedAt(issuedAt).expiresAt(issuedAt.plus(settings.getAccessTokenTtl()))
                .id(UUID.randomUUID().toString()).claim("role", user.getRole().name())
                .claim("token_version", user.getTokenVersion()).build();
        String value = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
        return new TokenResponse(value, "Bearer", settings.getAccessTokenTtl().toSeconds());
    }
}
