package org.university.judge.api.auth;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.university.judge.api.JudgeApiApplication;
import org.university.judge.api.support.TestJwtKeys;
import org.university.judge.api.user.*;
import org.university.judge.testsupport.AuthorizationProbes;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Spring OIDC/resource-server filters, signed JWTs, HTTP provider and PostgreSQL migrations. */
@Testcontainers
class PortalAuthenticationIT {
    @Container static final org.testcontainers.containers.GenericContainer<?> REDIS =
            new org.testcontainers.containers.GenericContainer<>("redis:8-alpine").withExposedPorts(6379);
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withPassword(UUID.randomUUID().toString());
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String CLIENT_ID = "judge-api";
    static final String SECRET = UUID.randomUUID().toString();
    static final Map<String, Map<String, Object>> PROFILES = new ConcurrentHashMap<>();
    static final Map<String, Login> CODES = new ConcurrentHashMap<>();
    static final Set<String> REVOKED = ConcurrentHashMap.newKeySet();
    static final AtomicInteger USERINFO_CALLS = new AtomicInteger();
    static volatile boolean unavailable;
    static HttpServer portal;
    static ConfigurableApplicationContext context;
    static String issuer, base;
    static String[] applicationArguments;
    static JwtEncoder encoder;
    static JwtDecoder providerDecoder;
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @BeforeAll static void start() throws Exception {
        var keys = TestJwtKeys.generate();
        RSAKey key = new RSAKey.Builder((RSAPublicKey) keys.getPublic()).privateKey((RSAPrivateKey) keys.getPrivate()).keyID("portal-key").build();
        encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        providerDecoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keys.getPublic()).build();
        portal = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        issuer = "http://127.0.0.1:" + portal.getAddress().getPort();
        portal.createContext("/.well-known/openid-configuration", exchange -> json(exchange, 200, Map.of(
                "issuer", issuer, "authorization_endpoint", issuer + "/oauth2/authorize", "token_endpoint", issuer + "/oauth2/token",
                "jwks_uri", issuer + "/oauth2/jwks", "userinfo_endpoint", issuer + "/userinfo",
                "response_types_supported", List.of("code"), "subject_types_supported", List.of("public"),
                "id_token_signing_alg_values_supported", List.of("RS256"), "token_endpoint_auth_methods_supported", List.of("client_secret_basic"))));
        portal.createContext("/oauth2/jwks", exchange -> json(exchange, 200, new JWKSet(key.toPublicJWK()).toJSONObject()));
        portal.createContext("/userinfo", exchange -> {
            USERINFO_CALLS.incrementAndGet();
            if (unavailable) { json(exchange, 503, Map.of("error", "sensitive-provider-error")); return; }
            try {
                String raw = exchange.getRequestHeaders().getFirst("Authorization").substring(7);
                Jwt jwt = providerDecoder.decode(raw);
                var profile = PROFILES.get(jwt.getSubject());
                if (profile == null || REVOKED.contains(raw)) { json(exchange, 401, Map.of("error", "invalid_token")); return; }
                json(exchange, 200, profile);
            } catch (Exception e) { json(exchange, 401, Map.of("error", "invalid_token")); }
        });
        portal.createContext("/oauth2/token", exchange -> {
            try {
                var form = parameters(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String expected = "Basic " + Base64.getEncoder().encodeToString((CLIENT_ID + ":" + SECRET).getBytes(StandardCharsets.UTF_8));
                Login login = CODES.remove(form.get("code"));
                String verifier = form.get("code_verifier");
                if (!expected.equals(exchange.getRequestHeaders().getFirst("Authorization")) || login == null || verifier == null
                        || !"authorization_code".equals(form.get("grant_type"))
                        || !login.authorization().get("redirect_uri").equals(form.get("redirect_uri"))
                        || !login.authorization().get("code_challenge").equals(Base64.getUrlEncoder().withoutPadding()
                            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII))))) {
                    json(exchange, 400, Map.of("error", "invalid_grant")); return;
                }
                String access = access(login.subject());
                Map<String, Object> claims = new HashMap<>(PROFILES.get(login.subject()));
                claims.put("nonce", login.badNonce() ? "wrong-nonce" : login.authorization().get("nonce"));
                String id = signed(login.subject(), issuer, CLIENT_ID, Instant.now().plusSeconds(1800), claims);
                json(exchange, 200, Map.of("access_token", access, "id_token", id, "token_type", "Bearer",
                        "expires_in", 1800, "scope", "openid profile email unicloud"));
            } catch (Exception e) { json(exchange, 400, Map.of("error", "invalid_grant")); }
        });
        portal.start();
        var args = new ArrayList<>(List.of(TestJwtKeys.arguments("--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(), "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(), "--spring.data.redis.password=",
                "--spring.data.redis.host=" + REDIS.getHost(), "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                "--security.authentication-mode=portal", "--security.portal.issuer=" + issuer,
                "--security.portal.client-id=" + CLIENT_ID, "--security.portal.client-secret=" + SECRET,
                "--security.portal.redirect-uri=http://localhost/login/oauth2/code/portal", "--security.portal.admin-subjects=portal-admin")));
        // Portal mode must start without any local signing keys, passwords or token issuer.
        args.removeIf(value -> value.startsWith("--security.jwt."));
        applicationArguments = args.toArray(String[]::new);
        context = new SpringApplicationBuilder(JudgeApiApplication.class, AuthorizationProbes.class).run(applicationArguments);
        base = "http://localhost:" + context.getEnvironment().getRequiredProperty("local.server.port");
    }

    @AfterAll static void stop() { if (context != null) context.close(); if (portal != null) portal.stop(0); }

    @Test void bearerUsesUserinfoAndStableExternalIdentityWithoutPasswords() throws Exception {
        String sub = "42"; // Portal subjects are opaque strings, commonly numeric, not application UUIDs.
        PROFILES.put(sub, claims(sub, "STUDENT", List.of("STUDENT_PROFILE_READ_OWN", "COURSE_OFFERING_READ")));
        String token = access(sub);
        assertThat(providerDecoder.decode(token).getClaims()).doesNotContainKeys("role", "permissions");
        var response = get(HTTP, "/api/users/me", token);
        assertThat(response.statusCode()).isEqualTo(200);
        UUID id = UUID.fromString(JSON.readTree(response.body()).get("id").asText());
        assertThat(get(HTTP, "/api/users/me", token).body()).isEqualTo(response.body());
        User user = context.getBean(UserRepository.class).findById(id).orElseThrow();
        assertThat(user.isExternal()).isTrue();
        assertThat(user.getPasswordHash()).isNull();
        assertThat(response.body()).doesNotContain(token, SECRET, "externalSubject");
        assertThat(response.headers().firstValue("set-cookie")).isEmpty();
    }

    @Test void doesNotLinkByEmailOrModifyExistingLocalAccount() throws Exception {
        String sub = profile("STUDENT");
        String email = PROFILES.get(sub).get("email").toString();
        User local = context.getBean(UserRepository.class).saveAndFlush(new User("local-" + UUID.randomUUID(), email, "unusable-test-hash", Role.ADMIN));
        var response = get(HTTP, "/api/users/me", access(sub));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(response.body()).get("id").asText()).isNotEqualTo(local.getId().toString());
        assertThat(context.getBean(UserRepository.class).findByEmailIgnoreCase(email).orElseThrow().getId()).isEqualTo(local.getId());
    }

    @Test void rejectsOtherAudiencesIdTokensMissingScopeWrongIssuerExpiredAndForgedTokensBeforeUserinfo() throws Exception {
        String sub = profile("STUDENT");
        int calls = USERINFO_CALLS.get();
        var scopes = Map.<String, Object>of("scope", List.of("openid", "unicloud"));
        for (String token : List.of(
                signed(sub, issuer, "library", Instant.now().plusSeconds(600), scopes),
                signed(sub, issuer, "unicloud-api", Instant.now().plusSeconds(600), scopes),
                signed(sub, issuer, CLIENT_ID, Instant.now().plusSeconds(600), PROFILES.get(sub)),
                signed(sub, issuer, CLIENT_ID, Instant.now().plusSeconds(600), Map.of("scope", List.of("openid"))),
                signed(sub, issuer + "/wrong", CLIENT_ID, Instant.now().plusSeconds(600), scopes),
                signed(sub, issuer, CLIENT_ID, Instant.now().minusSeconds(120), scopes), "not-a-jwt")) {
            assertThat(get(HTTP, "/api/users/me", token).statusCode()).isEqualTo(401);
        }
        var keys = TestJwtKeys.generate();
        var rogue = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(new RSAKey.Builder((RSAPublicKey) keys.getPublic())
                .privateKey((RSAPrivateKey) keys.getPrivate()).keyID("portal-key").build())));
        String forged = rogue.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).keyId("portal-key").build(),
                JwtClaimsSet.builder().issuer(issuer).subject(sub).audience(List.of(CLIENT_ID)).issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(600)).claim("scope", List.of("openid", "unicloud")).build())).getTokenValue();
        assertThat(get(HTTP, "/api/users/me", forged).statusCode()).isEqualTo(401);
        assertThat(USERINFO_CALLS.get()).isEqualTo(calls);
    }

    @Test void enforcesRolePermissionsAndExplicitAdministratorMapping() throws Exception {
        assertThat(get(HTTP, "/api/teacher/probe", access(profile("STUDENT"))).statusCode()).isEqualTo(403);
        String teacher = profile("TEACHER");
        assertThat(get(HTTP, "/api/teacher/probe", access(teacher)).statusCode()).isEqualTo(200);
        assertThat(get(HTTP, "/api/admin/probe", access(teacher)).statusCode()).isEqualTo(403);
        for (String role : List.of("INSPECTOR", "ADMIN", "UNKNOWN")) {
            assertThat(get(HTTP, "/api/users/me", access(profile(role))).statusCode()).isEqualTo(403);
        }
        PROFILES.put("portal-admin", claims("portal-admin", "ADMIN", List.of("STAFF_CREATE")));
        assertThat(get(HTTP, "/api/admin/probe", access("portal-admin")).statusCode()).isEqualTo(200);
        PROFILES.put(teacher, claims(teacher, "TEACHER", List.of()));
        assertThat(get(HTTP, "/api/teacher/probe", access(teacher)).statusCode()).isEqualTo(403);
    }

    @Test void roleChangesAndRevocationAreAppliedOnNextRequest() throws Exception {
        String sub = profile("TEACHER");
        String token = access(sub);
        assertThat(get(HTTP, "/api/teacher/probe", token).statusCode()).isEqualTo(200);
        PROFILES.put(sub, claims(sub, "STUDENT", List.of("STUDENT_PROFILE_READ_OWN", "COURSE_OFFERING_READ")));
        assertThat(get(HTTP, "/api/teacher/probe", token).statusCode()).isEqualTo(403);
        assertThat(JSON.readTree(get(HTTP, "/api/users/me", token).body()).get("role").asText()).isEqualTo("STUDENT");
        REVOKED.add(token);
        assertThat(get(HTTP, "/api/users/me", token).statusCode()).isEqualTo(401);
    }

    @Test void localDisablingCannotBeUndoneByPortalLogin() throws Exception {
        String sub = profile("STUDENT"), token = access(sub);
        UUID id = UUID.fromString(JSON.readTree(get(HTTP, "/api/users/me", token).body()).get("id").asText());
        context.getBean(JdbcTemplate.class).update("UPDATE users SET enabled = false WHERE id = ?", id);
        assertThat(get(HTTP, "/api/users/me", token).statusCode()).isEqualTo(401);
        assertThat(context.getBean(UserRepository.class).findById(id).orElseThrow().isEnabled()).isFalse();
    }

    @Test void concurrentFirstRequestsCreateOneIdentity() throws Exception {
        String sub = profile("STUDENT"), token = access(sub);
        var request = HttpRequest.newBuilder(URI.create(base + "/api/users/me")).header("Authorization", "Bearer " + token).GET().build();
        var requests = java.util.stream.IntStream.range(0, 8)
                .mapToObj(i -> HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())).toList();
        Set<String> ids = new HashSet<>();
        for (var future : requests) {
            var response = future.get();
            assertThat(response.statusCode()).isEqualTo(200);
            ids.add(JSON.readTree(response.body()).get("id").asText());
        }
        assertThat(ids).hasSize(1);
        assertThat(context.getBean(JdbcTemplate.class).queryForObject(
                "SELECT count(*) FROM users WHERE external_issuer = ? AND external_subject = ?", Integer.class, issuer, sub)).isEqualTo(1);
    }

    @Test void externalRoleChangeInvalidatesInFlightApplicationIdentityAndPortalAdminCanBeDemoted() throws Exception {
        PROFILES.put("portal-admin", claims("portal-admin", "ADMIN", List.of("STAFF_CREATE")));
        String token = access("portal-admin");
        var profile = get(HTTP, "/api/users/me", token);
        UUID id = UUID.fromString(JSON.readTree(profile.body()).get("id").asText());
        long version = context.getBean(UserRepository.class).findById(id).orElseThrow().getTokenVersion();
        PROFILES.put("portal-admin", claims("portal-admin", "TEACHER", List.of("COURSE_OFFERING_READ")));
        assertThat(get(HTTP, "/api/teacher/probe", token).statusCode()).isEqualTo(200);
        assertThat(get(HTTP, "/api/admin/probe", token).statusCode()).isEqualTo(403);
        assertThat(context.getBean(UserRepository.class).findById(id).orElseThrow().getTokenVersion()).isGreaterThan(version);
    }

    @Test void missingTimeClaimsAreRejectedAndSpaceDelimitedScopesAreAccepted() throws Exception {
        String sub = profile("STUDENT");
        for (boolean missingExpiry : List.of(true, false)) {
            var claims = JwtClaimsSet.builder().issuer(issuer).subject(sub).audience(List.of(CLIENT_ID))
                    .claim("scope", "openid unicloud");
            if (missingExpiry) claims.issuedAt(Instant.now()); else claims.expiresAt(Instant.now().plusSeconds(600));
            String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).keyId("portal-key").build(), claims.build())).getTokenValue();
            assertThat(get(HTTP, "/api/users/me", token).statusCode()).isEqualTo(401);
        }
        assertThat(get(HTTP, "/api/users/me", signed(sub, issuer, CLIENT_ID, Instant.now().plusSeconds(600),
                Map.of("scope", "openid profile email unicloud"))).statusCode()).isEqualTo(200);
    }

    @Test void rejectsUserinfoSubjectMismatchAndFailsClosedOnProviderOutage() throws Exception {
        String sub = profile("STUDENT"), token = access(sub);
        PROFILES.put(sub, claims("another-person", "STUDENT", List.of("STUDENT_PROFILE_READ_OWN", "COURSE_OFFERING_READ")));
        assertThat(get(HTTP, "/api/users/me", token).statusCode()).isEqualTo(401);
        try {
            unavailable = true;
            var response = get(HTTP, "/api/users/me", token);
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.body()).contains("IDENTITY_PROVIDER_UNAVAILABLE").doesNotContain(token, "sensitive-provider-error", SECRET);
        } finally { unavailable = false; }
    }

    @Test void confidentialOidcLoginUsesPkceNonceStateAndSecureSessionWithoutExposingTokens() throws Exception {
        Browser browser = browser();
        var start = get(browser.http(), "/oauth2/authorization/portal", null);
        assertThat(start.statusCode()).isEqualTo(302);
        assertThat(start.headers().allValues("set-cookie").toString()).contains("HttpOnly", "Secure", "SameSite=Lax");
        Map<String, String> auth = parameters(URI.create(start.headers().firstValue("location").orElseThrow()).getRawQuery());
        assertThat(auth).containsEntry("code_challenge_method", "S256").containsEntry("client_id", CLIENT_ID);
        assertThat(auth.get("scope").split(" ")).contains("openid", "profile", "email", "unicloud");
        assertThat(auth.get("nonce")).isNotBlank();
        assertThat(auth.get("state")).isNotBlank();
        String sub = profile("TEACHER"), code = UUID.randomUUID().toString();
        CODES.put(code, new Login(sub, auth, false));
        var callback = get(browser.http(), "/login/oauth2/code/portal?code=" + code + "&state=" + encode(auth.get("state")), null);
        assertThat(callback.statusCode()).isEqualTo(302);
        assertThat(callback.headers().firstValue("location")).contains("/api/users/me");
        assertThat(callback.body()).doesNotContain("access_token", "id_token", SECRET);
        assertThat(get(browser.http(), "/api/teacher/probe", null).statusCode()).isEqualTo(200);
        // Supplying an invalid Bearer token must not fall back to the valid session.
        assertThat(get(browser.http(), "/api/users/me", "invalid").statusCode()).isEqualTo(401);
        assertThat(get(browser.http(), "/api/users/me", null).statusCode()).isEqualTo(200);
        assertThat(get(browser.http(), "/login/oauth2/code/portal?code=" + code + "&state=" + encode(auth.get("state")), null).statusCode()).isEqualTo(401);
    }

    @Test void rejectsInvalidStateNonceAndProviderErrorsWithoutLoginLoop() throws Exception {
        for (String error : List.of("state", "nonce", "denied")) {
            Browser browser = browser();
            var start = get(browser.http(), "/oauth2/authorization/portal", null);
            var auth = parameters(URI.create(start.headers().firstValue("location").orElseThrow()).getRawQuery());
            String code = UUID.randomUUID().toString();
            CODES.put(code, new Login(profile("STUDENT"), auth, error.equals("nonce")));
            String path = "/login/oauth2/code/portal?" + (error.equals("denied") ? "error=access_denied" : "code=" + code)
                    + "&state=" + encode(error.equals("state") ? "wrong-state" : auth.get("state"));
            var callback = get(browser.http(), path, null);
            assertThat(callback.statusCode()).isEqualTo(401);
            assertThat(callback.headers().firstValue("location")).isEmpty();
            assertThat(get(browser.http(), "/api/users/me", null).statusCode()).isEqualTo(401);
        }
    }

    @Test void browserWritesAndLogoutRequireCsrfBearerWritesDoNotAndOwnershipSurvives() throws Exception {
        String teacher = profile("TEACHER");
        Browser browser = login(teacher);
        var body = Map.of("title", "Portal assignment", "description", "test", "maximumScore", 10,
                "deadline", Instant.now().plusSeconds(3600).toString());
        assertThat(post(browser.http(), "/api/assignments", body, null, null).statusCode()).isEqualTo(403);
        String csrf = JSON.readTree(get(browser.http(), "/auth/csrf", null).body()).get("token").asText();
        var created = post(browser.http(), "/api/assignments", body, null, csrf);
        assertThat(created.statusCode()).isEqualTo(201);
        String path = created.headers().firstValue("location").orElseThrow();
        assertThat(get(HTTP, path, access(teacher)).statusCode()).isEqualTo(200);
        assertThat(get(HTTP, path, access(profile("TEACHER"))).statusCode()).isEqualTo(404);
        assertThat(get(HTTP, path, access(profile("STUDENT"))).statusCode()).isEqualTo(404);
        assertThat(post(HTTP, "/api/assignments", body, access(teacher), null).statusCode()).isEqualTo(201);
        assertThat(post(browser.http(), "/auth/logout", Map.of(), null, null).statusCode()).isEqualTo(403);
        assertThat(post(browser.http(), "/auth/logout", Map.of(), null, csrf).statusCode()).isEqualTo(204);
        assertThat(get(browser.http(), "/api/users/me", null).statusCode()).isEqualTo(401);
    }

    @Test void sessionChecksProviderAndLocalCredentialEndpointsAndWorkerStayIsolated() throws Exception {
        String sub = profile("STUDENT");
        Browser browser = login(sub);
        assertThat(get(browser.http(), "/api/users/me", null).statusCode()).isEqualTo(200);
        PROFILES.remove(sub);
        assertThat(get(browser.http(), "/api/users/me", null).statusCode()).isEqualTo(401);
        String token = access(profile("STUDENT"));
        assertThat(post(HTTP, "/api/auth/login", Map.of("identifier", "x", "password", "x"), token, null).statusCode()).isEqualTo(403);
        assertThat(post(HTTP, "/api/auth/register", Map.of(), token, null).statusCode()).isEqualTo(403);
        assertThat(post(HTTP, "/internal/worker/v1/submissions/" + UUID.randomUUID() + "/attempts", Map.of(), token, null).statusCode()).isEqualTo(401);
        assertThat(context.getBeansOfType(AuthenticationService.class)).isEmpty();
        assertThat(context.getBeansOfType(JwtEncoder.class)).isEmpty();
    }

    @Test void oidcCallbackAndAuthenticatedSessionCanMoveBetweenApiReplicas() throws Exception {
        String originalBase = base;
        Browser browser = browser();
        var auth = parameters(URI.create(get(browser.http(), "/oauth2/authorization/portal", null)
                .headers().firstValue("location").orElseThrow()).getRawQuery());
        String code = UUID.randomUUID().toString();
        CODES.put(code, new Login(profile("TEACHER"), auth, false));
        try (var replica = new SpringApplicationBuilder(JudgeApiApplication.class, AuthorizationProbes.class).run(applicationArguments)) {
            String replicaBase = "http://localhost:" + replica.getEnvironment().getRequiredProperty("local.server.port");
            base = replicaBase;
            assertThat(get(browser.http(), "/login/oauth2/code/portal?code=" + code + "&state=" + encode(auth.get("state")), null)
                    .statusCode()).isEqualTo(302);
            assertThat(get(browser.http(), "/api/teacher/probe", null).statusCode()).isEqualTo(200);
            base = originalBase;
            assertThat(get(browser.http(), "/api/teacher/probe", null).statusCode()).isEqualTo(200);
            String csrf = JSON.readTree(get(browser.http(), "/auth/csrf", null).body()).get("token").asText();
            base = replicaBase;
            assertThat(post(browser.http(), "/auth/logout", Map.of(), null, csrf).statusCode()).isEqualTo(204);
            base = originalBase;
            assertThat(get(browser.http(), "/api/users/me", null).statusCode()).isEqualTo(401);
        } finally { base = originalBase; }
    }

    static Browser login(String sub) throws Exception {
        Browser browser = browser();
        var auth = parameters(URI.create(get(browser.http(), "/oauth2/authorization/portal", null).headers().firstValue("location").orElseThrow()).getRawQuery());
        String code = UUID.randomUUID().toString();
        CODES.put(code, new Login(sub, auth, false));
        assertThat(get(browser.http(), "/login/oauth2/code/portal?code=" + code + "&state=" + encode(auth.get("state")), null).statusCode()).isEqualTo(302);
        return browser;
    }

    static Browser browser() {
        // Java's CookieManager does not implement browsers' secure localhost exception.
        // Tests retain the production Secure flag on the response, then replay only in this loopback client.
        CookieManager jar = new CookieManager(null, CookiePolicy.ACCEPT_ALL) {
            @Override public void put(URI uri, Map<String, List<String>> headers) throws java.io.IOException {
                super.put(uri, headers);
                getCookieStore().getCookies().forEach(cookie -> cookie.setSecure(false));
            }
        };
        return new Browser(HttpClient.newBuilder().cookieHandler(jar).build());
    }

    static String profile(String role) {
        String sub = UUID.randomUUID().toString();
        PROFILES.put(sub, claims(sub, role, List.of("STUDENT_PROFILE_READ_OWN", "COURSE_OFFERING_READ")));
        return sub;
    }
    static Map<String, Object> claims(String sub, String role, List<String> permissions) {
        return Map.of("sub", sub, "role", role, "permissions", permissions, "email", sub + "@example.test", "name", "Portal Test");
    }
    static String access(String sub) { return signed(sub, issuer, CLIENT_ID, Instant.now().plusSeconds(1800), Map.of("scope", List.of("openid", "profile", "email", "unicloud"))); }
    static String signed(String sub, String iss, String aud, Instant exp, Map<String, Object> extra) {
        var claims = JwtClaimsSet.builder().issuer(iss).subject(sub).audience(List.of(aud)).issuedAt(Instant.now().minusSeconds(180))
                .expiresAt(exp).id(UUID.randomUUID().toString()).claims(values -> values.putAll(extra)).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).keyId("portal-key").build(), claims)).getTokenValue();
    }
    static HttpResponse<String> get(HttpClient client, String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    static HttpResponse<String> post(HttpClient client, String path, Object body, String token, String csrf) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (csrf != null) request.header("X-CSRF-TOKEN", csrf);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    static Map<String, String> parameters(String raw) {
        Map<String, String> result = new HashMap<>();
        for (String pair : raw.split("&")) { String[] parts = pair.split("=", 2); result.put(decode(parts[0]), decode(parts[1])); }
        return result;
    }
    static String decode(String value) { return URLDecoder.decode(value, StandardCharsets.UTF_8); }
    static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    static void json(HttpExchange exchange, int status, Object body) throws java.io.IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
    record Login(String subject, Map<String, String> authorization, boolean badNonce) { }
    record Browser(HttpClient http) { }
}
