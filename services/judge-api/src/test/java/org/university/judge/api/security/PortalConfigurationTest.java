package org.university.judge.api.security;

import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.university.judge.api.security.portal.PortalSettings;

import static org.assertj.core.api.Assertions.*;

class PortalConfigurationTest {
    @Test void rejectsUnknownAuthenticationMode() {
        new ApplicationContextRunner().withUserConfiguration(AuthenticationModeConfiguration.class)
                .withPropertyValues("security.authentication-mode=poratl").run(context -> assertThat(context).hasFailed());
    }

    @Test void rejectsUnsafeConfigurationAndRedactsSecret() {
        for (String issuer : new String[]{"http://portal.example", "https://portal.example/path", "https://user:secret@portal.example", "https://portal.example?x=y"}) {
            assertThatThrownBy(() -> settings(issuer, "judge-api", "https://judge.example/login/oauth2/code/portal"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> settings("https://portal.example", "unicloud-api", "https://judge.example/callback"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings("https://portal.example", "judge-api", "http://judge.example/callback"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(settings("https://portal.example", "judge-api", "http://localhost:8080/login/oauth2/code/portal").toString())
                .doesNotContain("test-client-secret");
    }

    private static PortalSettings settings(String issuer, String clientId, String callback) {
        return new PortalSettings(issuer, clientId, "test-client-secret", callback, Set.of(), Duration.ofSeconds(10));
    }
}
