package org.university.judge.api.security.portal;

import java.net.URI;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("security.portal")
public record PortalSettings(String issuer, String clientId, String clientSecret, String redirectUri,
        Set<String> adminSubjects, Duration requestTimeout) {
    public PortalSettings {
        requireUrl(issuer);
        requireUrl(redirectUri);
        if (issuer.length() > 512 || !URI.create(issuer).getPath().isEmpty()
                || clientId == null || !clientId.matches("[a-z0-9-]{2,63}")
                || "unicloud-api".equals(clientId) || clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalArgumentException("Configure the Portal issuer, dedicated client id, secret and callback");
        }
        adminSubjects = adminSubjects == null ? Set.of() : Set.copyOf(adminSubjects);
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(10) : requestTimeout;
        if (requestTimeout.compareTo(Duration.ofSeconds(1)) < 0 || requestTimeout.compareTo(Duration.ofMinutes(3)) > 0) {
            throw new IllegalArgumentException("Portal request timeout must be between 1 second and 3 minutes");
        }
    }

    private static void requireUrl(String value) {
        URI uri;
        try { uri = URI.create(value); } catch (RuntimeException e) { throw new IllegalArgumentException("Invalid Portal URL"); }
        boolean loopback = Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost() == null ? "" : uri.getHost());
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null
                || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && loopback))) {
            throw new IllegalArgumentException("Portal URLs require HTTPS (HTTP allowed only on loopback)");
        }
    }

    @Override public String toString() { return "PortalSettings[redacted]"; }
}
