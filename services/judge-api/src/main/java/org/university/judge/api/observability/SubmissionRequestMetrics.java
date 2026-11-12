package org.university.judge.api.observability;
import java.io.IOException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SubmissionRequestMetrics extends OncePerRequestFilter {
    private final MeterRegistry meters;
    public SubmissionRequestMetrics(MeterRegistry meters) { this.meters = meters; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getMethod().equals("POST") || !request.getRequestURI().matches("/api/assignments/[^/]+/submissions");
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        var sample = Timer.start(meters);
        boolean failed = true;
        try { chain.doFilter(request, response); failed = false; }
        finally {
            String outcome = failed || response.getStatus() >= 500 ? "error" : response.getStatus() == 201 ? "accepted" : "rejected";
            sample.stop(Timer.builder("judge.submission.acceptance").tag("outcome", outcome)
                    .description("Servlet entry through multipart parsing, authentication, storage and commit; includes idempotent replays")
                    .publishPercentileHistogram().serviceLevelObjectives(java.time.Duration.ofMillis(500), java.time.Duration.ofSeconds(2)).register(meters));
        }
    }
}

