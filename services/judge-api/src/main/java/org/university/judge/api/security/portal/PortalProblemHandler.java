package org.university.judge.api.security.portal;

import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.university.judge.api.error.ApiProblems;
import org.university.judge.api.security.SecurityProblemHandler;
import tools.jackson.databind.ObjectMapper;

public class PortalProblemHandler implements AuthenticationEntryPoint {
    private final SecurityProblemHandler delegate;
    private final ObjectMapper mapper;
    public PortalProblemHandler(SecurityProblemHandler delegate, ObjectMapper mapper) { this.delegate = delegate; this.mapper = mapper; }

    @Override public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e) throws IOException {
        String code = e instanceof OAuth2AuthenticationException oauth ? oauth.getError().getErrorCode() : "invalid_token";
        if (!code.equals("temporarily_unavailable") && !code.equals("insufficient_scope")) {
            delegate.commence(request, response, e); return;
        }
        HttpStatus status = code.equals("temporarily_unavailable") ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.FORBIDDEN;
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        mapper.writeValue(response.getOutputStream(), ApiProblems.create(status,
                status == HttpStatus.FORBIDDEN ? "FORBIDDEN" : "IDENTITY_PROVIDER_UNAVAILABLE",
                status == HttpStatus.FORBIDDEN ? "No Judge access is granted to this identity" : "Identity provider is temporarily unavailable",
                request.getRequestURI()));
    }
}
