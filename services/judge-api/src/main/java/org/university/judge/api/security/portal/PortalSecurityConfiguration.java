package org.university.judge.api.security.portal;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.client.RestClient;
import org.university.judge.api.security.SecurityProblemHandler;
import org.university.judge.api.user.UserRepository;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "security.authentication-mode", havingValue = "portal")
@EnableConfigurationProperties(PortalSettings.class)
@EnableMethodSecurity
public class PortalSecurityConfiguration {
    @Bean
    ClientRegistrationRepository portalClients(PortalSettings settings) {
        var client = ClientRegistrations.fromIssuerLocation(settings.issuer()).registrationId("portal")
                .clientId(settings.clientId()).clientSecret(settings.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .redirectUri(settings.redirectUri()).scope("openid", "profile", "email", "unicloud").build();
        return new InMemoryClientRegistrationRepository(client);
    }

    @Bean OAuth2AuthorizedClientRepository portalAuthorizedClients() { return new HttpSessionOAuth2AuthorizedClientRepository(); }

    @Bean
    PortalIdentityService portalIdentities(PortalSettings settings, UserRepository users, JdbcTemplate jdbc) {
        return new PortalIdentityService(settings, users, jdbc);
    }

    @Bean
    JwtDecoder portalAccessTokenDecoder(PortalSettings settings, ClientRegistrationRepository clients) {
        var decoder = NimbusJwtDecoder.withJwkSetUri(clients.findByRegistrationId("portal").getProviderDetails().getJwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ofSeconds(30)), new JwtIssuerValidator(settings.issuer()),
                new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(settings.clientId())),
                new JwtClaimValidator<Instant>("exp", exp -> exp != null),
                new JwtClaimValidator<Instant>("iat", iat -> iat != null && !iat.isAfter(Instant.now().plusSeconds(30))),
                new JwtClaimValidator<String>("sub", sub -> sub != null && !sub.isBlank() && sub.length() <= 255),
                // ID tokens have no OAuth scope. Never accept an ID token as an API credential.
                new JwtClaimValidator<Object>("scope", PortalSecurityConfiguration::hasRequiredScopes)));
        return decoder;
    }

    private static boolean hasRequiredScopes(Object claim) {
        Collection<?> scopes = claim instanceof String text ? List.of(text.split(" "))
                : claim instanceof Collection<?> values ? values : List.of();
        return scopes.contains("openid") && scopes.contains("unicloud");
    }

    @Bean
    PortalJwtConverter portalJwtConverter(PortalSettings settings, ClientRegistrationRepository clients, PortalIdentityService identities) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(settings.requestTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(settings.requestTimeout());
        return new PortalJwtConverter(RestClient.builder().requestFactory(factory).build(),
                clients.findByRegistrationId("portal").getProviderDetails().getUserInfoEndpoint().getUri(), identities);
    }

    @Bean PortalProblemHandler portalProblems(SecurityProblemHandler delegate, ObjectMapper mapper) {
        return new PortalProblemHandler(delegate, mapper);
    }

    @Bean @Order(2)
    SecurityFilterChain portalBearerChain(HttpSecurity http, JwtDecoder decoder, PortalJwtConverter converter,
            PortalProblemHandler problems, SecurityProblemHandler denied) throws Exception {
        // A supplied Authorization header always selects stateless authentication; cookies cannot rescue a bad token.
        http.securityMatcher(request -> request.getHeader("Authorization") != null)
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .exceptionHandling(errors -> errors.authenticationEntryPoint(problems).accessDeniedHandler(denied))
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(problems).accessDeniedHandler(denied));
        authorize(http, false);
        return http.build();
    }

    @Bean @Order(3)
    SecurityFilterChain portalBrowserChain(HttpSecurity http, ClientRegistrationRepository clients,
            OAuth2AuthorizedClientRepository authorizedClients, JwtDecoder decoder, PortalJwtConverter converter,
            PortalIdentityService identities, PortalProblemHandler problems, SecurityProblemHandler denied) throws Exception {
        var resolver = new DefaultOAuth2AuthorizationRequestResolver(clients, "/oauth2/authorization");
        // Confidential clients also MUST use PKCE; Spring's public-client default alone is insufficient.
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        var userService = new OidcUserService();
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .securityContext(context -> context.requireExplicitSave(true))
                .requestCache(AbstractHttpConfigurer::disable)
                .exceptionHandling(errors -> errors.authenticationEntryPoint(problems).accessDeniedHandler(denied))
                .oauth2Login(login -> login.clientRegistrationRepository(clients).authorizedClientRepository(authorizedClients)
                        .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(resolver))
                        .userInfoEndpoint(info -> info.oidcUserService(request -> {
                            var user = userService.loadUser(request);
                            identities.resolve(user.getSubject(), user.getClaims());
                            return user;
                        }))
                        .successHandler((request, response, authentication) -> {
                            response.setStatus(302);
                            response.setHeader("Location", request.getContextPath() + "/api/users/me");
                        })
                        .failureHandler((request, response, failure) -> problems.commence(request, response, failure)))
                // Default CSRF protection remains enabled for all cookie-authenticated mutations.
                .logout(logout -> logout.logoutUrl("/auth/logout").invalidateHttpSession(true).clearAuthentication(true)
                        .deleteCookies("JUDGE_SESSION").logoutSuccessHandler((request, response, auth) -> response.setStatus(204)))
                .addFilterBefore(new PortalSessionFilter(authorizedClients, decoder, converter, problems), AuthorizationFilter.class);
        authorize(http, true);
        return http.build();
    }

    private static void authorize(HttpSecurity http, boolean browser) throws Exception {
        http.authorizeHttpRequests(auth -> {
            auth.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();
            if (browser) auth.requestMatchers(HttpMethod.GET, "/oauth2/authorization/portal", "/login/oauth2/code/portal", "/auth/csrf").permitAll();
            auth.requestMatchers("/api/auth/**").denyAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/health", "/actuator/health", "/actuator/health/**",
                            "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()
                    .requestMatchers("/api/admin/**").hasRole("ADMIN")
                    .requestMatchers("/actuator/prometheus", "/actuator/metrics", "/actuator/metrics/**").hasRole("ADMIN")
                    .requestMatchers("/api/teacher/**").hasAnyRole("TEACHER", "ADMIN")
                    .requestMatchers("/api/**").authenticated().anyRequest().denyAll();
        });
    }
}
