
package org.university.judge.api.security;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

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
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final int LOGIN_LIMIT = 5;
    private static final int REGISTER_LIMIT = 3;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT =
            new DefaultRedisScript<>(
                    """
                    local count = redis.call('INCR', KEYS[1])
                    if count == 1 then
                        redis.call('EXPIRE', KEYS[1], ARGV[1])
                    end
                    return count
                    """,
                    Long.class
            );

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public AuthRateLimitFilter(
            StringRedisTemplate redis,
            ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String path = request.getRequestURI();

        return !"/api/auth/login".equals(path)
                && !"/api/auth/register".equals(path);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        boolean login = "/api/auth/login".equals(request.getRequestURI());

        int limit = login ? LOGIN_LIMIT : REGISTER_LIMIT;
        String endpoint = login ? "login" : "register";

        String ip = request.getRemoteAddr();
        String key = "rate-limit:auth:" + endpoint + ":" + ip;

        try {
            Long count = redis.execute(
                    RATE_LIMIT_SCRIPT,
                    List.of(key),
                    String.valueOf(WINDOW.toSeconds())
            );

            if (count == null) {
                writeProblem(
                        response,
                        request,
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "RATE_LIMIT_SERVICE_UNAVAILABLE",
                        "Authentication protection is temporarily unavailable."
                );
                return;
            }

            if (count > limit) {
                response.setHeader("Retry-After", "60");

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
