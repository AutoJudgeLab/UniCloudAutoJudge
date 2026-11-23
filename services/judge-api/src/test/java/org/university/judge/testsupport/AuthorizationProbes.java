package org.university.judge.testsupport;

import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// Outside application scanning; explicitly registered only by AuthenticationIT.
@TestConfiguration(proxyBeanMethods = false)
@Import(AuthorizationProbes.ProbeController.class)
public class AuthorizationProbes {
    @RestController
    public static class ProbeController {
        @GetMapping("/api/admin/probe")
        @PreAuthorize("hasRole('ADMIN')")
        public Map<String, String> admin() { return Map.of("access", "admin"); }

        @GetMapping("/api/teacher/probe")
        @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
        public Map<String, String> teacher() { return Map.of("access", "teacher"); }
    }
}
