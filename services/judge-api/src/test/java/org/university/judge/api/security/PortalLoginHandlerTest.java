package org.university.judge.api.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.university.judge.api.security.portal.PortalBrowserSettings;
import org.university.judge.api.security.portal.PortalLoginHandler;
import org.university.judge.api.security.portal.PortalProblemHandler;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PortalLoginHandlerTest {
    @Test void allowsOnlyExplicitInternalDestinations() {
        for (String path : new String[]{"https://evil.example", "//evil.example", "/\\evil.example",
                "/assignments?next=https://evil.example", "/%2f%2fevil.example", "/assignments\r\nX-Test: 1", ""}) {
            assertThatThrownBy(() -> new PortalBrowserSettings(path)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(new PortalBrowserSettings(null).loginSuccessPath()).isEqualTo("/api/users/me");
        assertThat(new PortalBrowserSettings("/assignments").frontendEnabled()).isTrue();
    }

    @Test void browserRedirectDoesNotTrustRequestParametersOrExposeFailureDetails() throws Exception {
        var problems = mock(PortalProblemHandler.class);
        var handler = new PortalLoginHandler(new PortalBrowserSettings("/assignments"), problems);
        var request = new MockHttpServletRequest();
        request.setParameter("returnTo", "https://evil.example");
        var success = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(request, success, null);
        assertThat(success.getStatus()).isEqualTo(302);
        assertThat(success.getHeader("Location")).isEqualTo("/assignments");
        assertThat(success.getHeader("Cache-Control")).isEqualTo("no-store");
        var failure = new MockHttpServletResponse();
        handler.onAuthenticationFailure(request, failure, new BadCredentialsException("secret-token"));
        assertThat(failure.getStatus()).isEqualTo(302);
        assertThat(failure.getHeader("Location")).isEqualTo("/login?error=sso");
        assertThat(failure.getContentAsString()).doesNotContain("secret-token");
        verifyNoInteractions(problems);
    }

    @Test void apiOnlyModePreservesProblemResponsesAndProfileRedirect() throws Exception {
        var problems = mock(PortalProblemHandler.class);
        var handler = new PortalLoginHandler(new PortalBrowserSettings(null), problems);
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        var exception = new BadCredentialsException("invalid login");
        handler.onAuthenticationSuccess(request, response, null);
        assertThat(response.getHeader("Location")).isEqualTo("/api/users/me");
        handler.onAuthenticationFailure(request, response, exception);
        verify(problems).commence(request, response, exception);
    }
}
