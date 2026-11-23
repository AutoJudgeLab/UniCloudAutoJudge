package org.university.judge.api.security;

import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.university.judge.api.support.TestJwtKeys;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtConfigurationTest {
    @Test
    void missingAndMismatchedKeysFailWithoutLeakingConfiguration() {
        JwtSettings settings = settings();
        settings.setPrivateKeyBase64("sensitive-invalid-key");
        assertThatThrownBy(() -> new JwtConfiguration().signingKey(settings))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("sensitive-invalid-key");
        settings.setPrivateKeyBase64(null);
        assertThatThrownBy(() -> new JwtConfiguration().signingKey(settings)).isInstanceOf(IllegalStateException.class);
        settings.setPrivateKeyBase64(Base64.getEncoder().encodeToString(TestJwtKeys.generate().getPrivate().getEncoded()));
        assertThatThrownBy(() -> new JwtConfiguration().signingKey(settings)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void matchingKeysAreAcceptedAndTokenLifetimeIsBounded() {
        JwtSettings settings = settings();
        assertThat(new JwtConfiguration().signingKey(settings).isPrivate()).isTrue();
        settings.setAccessTokenTtl(Duration.ofDays(1));
        assertThatThrownBy(() -> new JwtConfiguration().signingKey(settings)).isInstanceOf(IllegalStateException.class);
    }

    private JwtSettings settings() {
        JwtSettings settings = new JwtSettings();
        settings.setIssuer("online-judge");
        settings.setAudience("judge-api");
        settings.setAccessTokenTtl(Duration.ofMinutes(15));
        settings.setPrivateKeyBase64(Base64.getEncoder().encodeToString(TestJwtKeys.KEYS.getPrivate().getEncoded()));
        settings.setPublicKeyBase64(Base64.getEncoder().encodeToString(TestJwtKeys.KEYS.getPublic().getEncoded()));
        return settings;
    }
}
