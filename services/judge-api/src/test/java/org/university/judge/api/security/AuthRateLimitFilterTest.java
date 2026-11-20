package org.university.judge.api.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class AuthRateLimitFilterTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final AuthRateLimitFilter filter = new AuthRateLimitFilter(redis, JsonMapper.builder().build(),
            new AuthRateLimitSettings(true, 5, 3, Duration.ofMinutes(1)));

    @Test void redisFailureReturns503WithoutAuthenticatingOrExposingTheFailure() throws Exception {
        when(redis.<Long>execute(any(), anyList(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("secret-host-and-password"));
        var response = new MockHttpServletResponse();
        var continued = new AtomicBoolean();
        filter.doFilter(request("/api/auth/login"), response, (req, res) -> continued.set(true));
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("RATE_LIMIT_SERVICE_UNAVAILABLE").doesNotContain("secret-host-and-password");
        assertThat(continued).isFalse();
    }

    @Test void decodedServletPathProtectsEncodedRoutesAndContextPaths() throws Exception {
        when(redis.<Long>execute(any(), anyList(), any(), any())).thenReturn(12L);
        var request = request("/api/auth/login");
        request.setContextPath("/judge");
        request.setRequestURI("/judge/api/auth/%6Cogin");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> fail("Rate-limited request reached authentication"));
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("12");
    }

    @Test void healthAndWorkerRequestsDoNotDependOnTheLoginLimiter() throws Exception {
        for (String path : new String[]{"/api/v1/health", "/internal/worker/v1/submissions/id/attempts"}) {
            var continued = new AtomicBoolean();
            filter.doFilter(request(path), new MockHttpServletResponse(), (req, res) -> continued.set(true));
            assertThat(continued).isTrue();
        }
        verifyNoInteractions(redis);
    }

    private static MockHttpServletRequest request(String path) {
        var request = new MockHttpServletRequest("POST", path);
        request.setServletPath(path);
        request.setRemoteAddr("127.0.0.1");
        return request;
    }
}
