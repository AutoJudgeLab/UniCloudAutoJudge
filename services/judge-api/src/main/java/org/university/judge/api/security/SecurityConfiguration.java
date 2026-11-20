package org.university.judge.api.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import jakarta.servlet.DispatcherType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.jwt.JwtDecoder;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@ConditionalOnProperty(name = "security.authentication-mode", havingValue = "local", matchIfMissing = true)
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder decoder,
            LocalUserJwtConverter converter, SecurityProblemHandler problems) throws Exception {
        return http
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/health", "/actuator/health",
                                "/actuator/health/**", "/v3/api-docs", "/v3/api-docs/**",
                                "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/actuator/prometheus", "/actuator/metrics", "/actuator/metrics/**").hasRole("ADMIN")
                        .requestMatchers("/api/teacher/**").hasAnyRole("TEACHER", "ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                // Credentials are accepted only as Bearer headers, never browser cookies.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .build();
    }
}
