package org.university.judge.api.security.portal;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Refresh application membership per request; keep the OIDC context only in the server session. */
final class PortalSessionFilter extends OncePerRequestFilter {
    private final OAuth2AuthorizedClientRepository clients;
    private final JwtDecoder decoder;
    private final PortalJwtConverter converter;
    private final PortalProblemHandler problems;
    PortalSessionFilter(OAuth2AuthorizedClientRepository clients, JwtDecoder decoder,
            PortalJwtConverter converter, PortalProblemHandler problems) {
        this.clients = clients; this.decoder = decoder; this.converter = converter; this.problems = problems;
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().startsWith("/api/") && !request.getServletPath().startsWith("/actuator/");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var original = SecurityContextHolder.getContext();
        if (!(original.getAuthentication() instanceof OAuth2AuthenticationToken oauth)) {
            chain.doFilter(request, response); return;
        }
        try {
            OAuth2AuthorizedClient client = clients.loadAuthorizedClient(oauth.getAuthorizedClientRegistrationId(), oauth, request);
            if (client == null) throw new BadCredentialsException("Session expired");
            var jwt = decoder.decode(client.getAccessToken().getTokenValue());
            // Bind server-side token storage to the identity authenticated by OIDC.
            if (!jwt.getSubject().equals(oauth.getName())) throw new BadCredentialsException("Invalid session identity");
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(converter.convert(jwt));
            SecurityContextHolder.setContext(context);
        } catch (JwtException | AuthenticationException e) {
            boolean unavailable = e instanceof OAuth2AuthenticationException failure
                    && "temporarily_unavailable".equals(failure.getError().getErrorCode());
            if (!unavailable && request.getSession(false) != null) request.getSession(false).invalidate();
            SecurityContextHolder.clearContext();
            problems.commence(request, response, e instanceof AuthenticationException auth ? auth : new BadCredentialsException("Session expired"));
            return;
        }
        try { chain.doFilter(request, response); }
        finally { SecurityContextHolder.setContext(original); }
    }
}
