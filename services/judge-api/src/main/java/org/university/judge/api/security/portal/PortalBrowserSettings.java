package org.university.judge.api.security.portal;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Only fixed, same-origin application destinations can receive an OIDC redirect. */
@ConfigurationProperties("security.portal")
public record PortalBrowserSettings(String loginSuccessPath) {
    public PortalBrowserSettings {
        loginSuccessPath = loginSuccessPath == null ? "/api/users/me" : loginSuccessPath;
        if (!Set.of("/api/users/me", "/assignments").contains(loginSuccessPath)) {
            throw new IllegalArgumentException("Portal login success path must be /api/users/me or /assignments");
        }
    }

    public boolean frontendEnabled() { return loginSuccessPath.equals("/assignments"); }
}
