package org.university.judge.api.security.portal;

import java.io.IOException;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

public record PortalLoginHandler(PortalBrowserSettings settings, PortalProblemHandler problems)
        implements AuthenticationSuccessHandler, AuthenticationFailureHandler {
    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) {
        redirect(request, response, settings.loginSuccessPath());
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException, ServletException {
        if (settings.frontendEnabled()) {
            // Provider messages, authorization codes and tokens never reach the redirect URL.
            redirect(request, response, "/login?error=sso");
        } else {
            problems.commence(request, response, exception);
        }
    }

    private static void redirect(HttpServletRequest request, HttpServletResponse response, String path) {
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Location", request.getContextPath() + path);
    }
}
