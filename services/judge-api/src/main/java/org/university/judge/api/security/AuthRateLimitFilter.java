
package org.university.judge.api.security;

import java.io.IOException;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import tools.jackson.databind.ObjectMapper;
import org.university.judge.api.error.ApiProblems;

@Component
@ConditionalOnProperty(name = "security.authentication-mode", havingValue = "local", matchIfMissing = true)
@EnableConfigurationProperties(AuthRateLimitSettings.class)
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT =
            new DefaultRedisScript<>(
                    """
                    local count = redis.call('INCR', KEYS[1])
                    if count == 1 or redis.call('TTL', KEYS[1]) < 0 then
                        redis.call('EXPIRE', KEYS[1], ARGV[1])
                    end
                    if count > tonumber(ARGV[2]) then
                        return math.max(1, redis.call('TTL', KEYS[1]))
                    end
                    return 0
                    """,
                    Long.class
            );

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final AuthRateLimitSettings settings;

    public AuthRateLimitFilter(
            StringRedisTemplate redis,
            ObjectMapper mapper, AuthRateLimitSettings settings) {
        this.redis = redis;
        this.mapper = mapper;
        this.settings = settings;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!settings.enabled() || !"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String path = request.getServletPath();

        return !"/api/auth/login".equals(path)
                && !"/api/auth/register".equals(path);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        boolean login = "/api/auth/login".equals(request.getServletPath());

        int limit = login ? settings.loginLimit() : settings.registerLimit();
        String endpoint = login ? "login" : "register";

        String ip = request.getRemoteAddr();
        String key = "rate-limit:auth:" + endpoint + ":" + ip;

        try {
            Long retryAfter = redis.execute(
                    RATE_LIMIT_SCRIPT,
                    List.of(key),
                    String.valueOf(settings.window().toSeconds()), String.valueOf(limit)
            );

            if (retryAfter == null) {
                writeProblem(
                        response,
                        request,
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "RATE_LIMIT_SERVICE_UNAVAILABLE",
                        "Authentication protection is temporarily unavailable."
                );
                return;
            }

            if (retryAfter > 0) {
                response.setHeader("Retry-After", retryAfter.toString());

                writeProblem(
                        response,
                        request,
                        HttpStatus.TOO_MANY_REQUESTS,
                        "RATE_LIMIT_EXCEEDED",
                        "Too many authentication requests. Try again later."
                );
                return;
            }

        } catch (RuntimeException exception) {
            writeProblem(
                    response,
                    request,
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "RATE_LIMIT_SERVICE_UNAVAILABLE",
                    "Authentication protection is temporarily unavailable."
            );
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void writeProblem(
            HttpServletResponse response,
            HttpServletRequest request,
            HttpStatus status,
            String code,
            String detail) throws IOException {

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        mapper.writeValue(
                response.getOutputStream(),
                ApiProblems.create(status, code, detail, request.getRequestURI())
        );
    }
}
