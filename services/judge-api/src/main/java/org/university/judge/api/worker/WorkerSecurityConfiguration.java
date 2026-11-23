package org.university.judge.api.worker;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.web.SecurityFilterChain;
import org.university.judge.api.security.SecurityProblemHandler;

/** A separate credential and filter chain: worker tokens never become local user identities. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkerTransportProperties.class)
public class WorkerSecurityConfiguration {
    @Bean
    @Order(1)
    SecurityFilterChain workerSecurity(HttpSecurity http, WorkerTransportProperties properties,
            SecurityProblemHandler problems) throws Exception {
        byte[] expected = digest(properties.token() == null ? "" : properties.token());
        return http.securityMatcher("/internal/worker/**")
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/internal/worker/v1/submissions/*/attempts",
                                "/internal/worker/v1/submissions/*/attempts/*/result",
                                "/internal/worker/v1/submissions/*/attempts/*/heartbeat").hasAuthority("EVALUATION_WRITE")
                        .anyRequest().denyAll())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(errors -> errors.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .oauth2ResourceServer(oauth -> oauth.opaqueToken(opaque -> opaque.introspector(token -> {
                    if (!properties.enabled() || !MessageDigest.isEqual(expected, digest(token))) {
                        throw new BadOpaqueTokenException("Invalid worker credential");
                    }
                    return new DefaultOAuth2AuthenticatedPrincipal("judge-worker", Map.of("sub", "judge-worker"),
                            List.of(new SimpleGrantedAuthority("EVALUATION_WRITE")));
                })).authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .build();
    }

    private static byte[] digest(String token) {
        try { return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is required", impossible); }
    }
}
