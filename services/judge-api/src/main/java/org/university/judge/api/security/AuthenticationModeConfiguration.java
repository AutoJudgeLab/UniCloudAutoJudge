package org.university.judge.api.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthenticationModeConfiguration.Settings.class)
public class AuthenticationModeConfiguration {
    public enum Mode { LOCAL, PORTAL }

    // Fail startup for a typo instead of falling through to Boot's default security.
    @ConfigurationProperties("security")
    public record Settings(Mode authenticationMode) {
        public Settings { if (authenticationMode == null) authenticationMode = Mode.LOCAL; }
    }
}
