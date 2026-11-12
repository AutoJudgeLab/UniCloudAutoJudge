package org.university.judge.api.auth;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.university.judge.api.JudgeApiApplication;
import org.university.judge.api.support.TestJwtKeys;
import org.university.judge.api.user.Role;
import org.university.judge.api.user.User;
import org.university.judge.api.user.UserRepository;
import org.university.judge.testsupport.AuthorizationProbes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class AuthenticationIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withPassword(UUID.randomUUID().toString());
    private static final String PASSWORD = "Strong9!" + UUID.randomUUID();
    private static final String ADMIN_PASSWORD = "Admin9!" + UUID.randomUUID();
    private static ConfigurableApplicationContext context;
    private static String base;
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @BeforeAll
    static void startApplication() {
        context = new SpringApplicationBuilder(JudgeApiApplication.class, AuthorizationProbes.class).run(TestJwtKeys.arguments(
                "--server.port=0", "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(), "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.data.redis.password=unused", "--management.health.redis.enabled=false",
                "--management.endpoint.health.group.readiness.include=readinessState,db",
                "--security.bootstrap.enabled=true", "--security.bootstrap.username=initial-admin",
                "--security.bootstrap.email=initial-admin@example.test", "--security.bootstrap.password=" + ADMIN_PASSWORD));
        base = "http://localhost:" + context.getEnvironment().getRequiredProperty("local.server.port");
    }

    @AfterAll
    static void closeApplication() {
        if (context != null) context.close();
    }

    @Test
    void registrationCreatesOnlyStudentAndHashesPassword() throws Exception {
        String name = name();
        HttpResponse<String> response = post("/api/auth/register", Map.of("username", " " + name.toUpperCase() + " ",
                "email", name.toUpperCase() + "@EXAMPLE.TEST", "password", PASSWORD));
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("role").asText()).isEqualTo("STUDENT");
        assertThat(body.get("username").asText()).isEqualTo(name);
        assertThat(body.get("email").asText()).isEqualTo(name + "@example.test");
        assertThat(response.body()).doesNotContain("password", "passwordHash", PASSWORD);
        User stored = context.getBean(UserRepository.class).findByUsername(name).orElseThrow();
        assertThat(stored.getRole()).isEqualTo(Role.STUDENT);
        assertThat(stored.isEnabled()).isTrue();
        assertThat(stored.getPasswordHash()).startsWith("$2").isNotEqualTo(PASSWORD);
        assertThat(context.getBean(PasswordEncoder.class).matches(PASSWORD, stored.getPasswordHash())).isTrue();
        assertThat(response.headers().firstValue("set-cookie")).isEmpty();
    }

    @Test
    void registrationRejectsRoleAndUnknownFields() throws Exception {
        for (String field : List.of("role", "enabled", "userId")) {
            String name = name();
            assertProblem(post("/api/auth/register", Map.of("username", name, "email", name + "@example.test",
                    "password", PASSWORD, field, "ADMIN")), 400, "INVALID_REQUEST");
            assertThat(context.getBean(UserRepository.class).findByUsername(name)).isEmpty();
        }
    }

    @Test
    void registrationRejectsDuplicateUsernameAndEmailIgnoringCase() throws Exception {
        Account account = register();
        assertProblem(post("/api/auth/register", Map.of("username", account.name().toUpperCase(),
                "email", name() + "@example.test", "password", PASSWORD)), 409, "ACCOUNT_CONFLICT");
        assertProblem(post("/api/auth/register", Map.of("username", name(),
                "email", account.name().toUpperCase() + "@EXAMPLE.TEST", "password", PASSWORD)), 409, "ACCOUNT_CONFLICT");
    }

    @Test
    void registrationValidatesUsernameEmailAndPassword() throws Exception {
        for (Map<String, String> payload : List.of(
                Map.of("username", "ab", "email", "a@example.test", "password", PASSWORD),
                Map.of("username", "a".repeat(51), "email", "a@example.test", "password", PASSWORD),
                Map.of("username", name(), "email", "invalid-email", "password", PASSWORD),
                Map.of("username", name(), "email", "a@example.test", "password", "weak"),
                Map.of("username", name(), "email", "a@example.test", "password", "Aa1!" + "é".repeat(35)))) {
            HttpResponse<String> response = post("/api/auth/register", payload);
            assertProblem(response, 400, "VALIDATION_FAILED");
            assertThat(response.body()).doesNotContain(PASSWORD);
        }
    }

    @Test
    void loginAcceptsUsernameOrEmailAndMeUsesSecurityContextIdentity() throws Exception {
        Account account = register();
        for (String identifier : List.of(account.name().toUpperCase(), account.name() + "@example.test")) {
            HttpResponse<String> response = post("/api/auth/login", Map.of("identifier", identifier, "password", PASSWORD));
            assertThat(response.statusCode()).isEqualTo(200);
            JsonNode body = JSON.readTree(response.body());
            assertThat(body.get("tokenType").asText()).isEqualTo("Bearer");
            assertThat(body.get("expiresIn").asLong()).isEqualTo(900);
            String token = body.get("accessToken").asText();
            var jwt = context.getBean(JwtDecoder.class).decode(token);
            assertThat(jwt.getSubject()).isEqualTo(account.id().toString());
            assertThat(jwt.getHeaders().get("alg")).isEqualTo("RS256");
            assertThat(jwt.getClaims()).doesNotContainKeys("email", "password", "passwordHash");
            HttpResponse<String> profile = get("/api/users/me?userId=" + UUID.randomUUID(), token);
            assertThat(profile.statusCode()).isEqualTo(200);
            assertThat(JSON.readTree(profile.body()).get("id").asText()).isEqualTo(account.id().toString());
            assertThat(profile.body()).doesNotContain("password", PASSWORD);
            assertThat(response.headers().firstValue("set-cookie")).isEmpty();
            assertThat(response.headers().firstValue("cache-control").orElse("")).contains("no-store");
        }
    }

    @Test
    void invalidPasswordAndUnknownOrDisabledUsersHaveSameLoginResponse() throws Exception {
        Account account = register();
        String invalid = post("/api/auth/login", Map.of("identifier", account.name(), "password", "WrongPassword9!")).body();
        HttpResponse<String> missing = post("/api/auth/login", Map.of("identifier", name(), "password", PASSWORD));
        assertProblem(missing, 401, "UNAUTHORIZED");
        assertThat(missing.body()).isEqualTo(invalid);
        context.getBean(JdbcTemplate.class).update("UPDATE users SET enabled = false WHERE id = ?", account.id());
        HttpResponse<String> disabled = post("/api/auth/login", Map.of("identifier", account.name(), "password", PASSWORD));
        assertProblem(disabled, 401, "UNAUTHORIZED");
        assertThat(disabled.body()).isEqualTo(invalid);
    }

    @Test
    void missingMalformedForgedAndExpiredTokensAreUnauthorized() throws Exception {
        Account account = register();
        assertProblem(get("/api/users/me", null), 401, "UNAUTHORIZED");
        assertProblem(get("/api/users/me", "not-a-jwt"), 401, "UNAUTHORIZED");
        var other = TestJwtKeys.generate();
        JwtEncoder forged = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(new RSAKey.Builder((RSAPublicKey) other.getPublic())
                .privateKey((RSAPrivateKey) other.getPrivate()).build())));
        assertProblem(get("/api/users/me", signed(forged, account.id().toString(), "online-judge", "judge-api",
                Instant.now().minusSeconds(5), Instant.now().plusSeconds(600))), 401, "UNAUTHORIZED");
        JwtEncoder encoder = context.getBean(JwtEncoder.class);
        assertProblem(get("/api/users/me", signed(encoder, account.id().toString(), "online-judge", "judge-api",
                Instant.now().minusSeconds(1200), Instant.now().minusSeconds(300))), 401, "UNAUTHORIZED");
        assertProblem(get("/api/users/me", signed(encoder, account.id().toString(), "wrong-issuer", "judge-api",
                Instant.now().minusSeconds(5), Instant.now().plusSeconds(600))), 401, "UNAUTHORIZED");
        assertProblem(get("/api/users/me", signed(encoder, account.id().toString(), "online-judge", "wrong-audience",
                Instant.now().minusSeconds(5), Instant.now().plusSeconds(600))), 401, "UNAUTHORIZED");
        assertProblem(get("/api/users/me", signed(encoder, "not-a-user-id", "online-judge", "judge-api",
                Instant.now().minusSeconds(5), Instant.now().plusSeconds(600))), 401, "UNAUTHORIZED");
        assertProblem(get("/api/users/me", signed(encoder, UUID.randomUUID().toString(), "online-judge", "judge-api",
                Instant.now().minusSeconds(5), Instant.now().plusSeconds(600))), 401, "UNAUTHORIZED");
        assertProblem(get("/api/users/me", signed(encoder, account.id().toString(), "online-judge", "judge-api",
                Instant.now().minusSeconds(5), null)), 401, "UNAUTHORIZED");
    }

    @Test
    void roleAuthorizationUsesCurrentLocalMembershipAndDisablingRevokesAccess() throws Exception {
        Account account = register();
        String token = login(account.name(), PASSWORD);
        assertProblem(get("/api/admin/probe", token), 403, "FORBIDDEN");
        assertProblem(get("/api/teacher/probe", token), 403, "FORBIDDEN");
        // The signed token deliberately advertises ADMIN; local membership wins.
        String elevatedClaim = signed(context.getBean(JwtEncoder.class), account.id().toString(), "online-judge", "judge-api",
                Instant.now().minusSeconds(5), Instant.now().plusSeconds(600));
        assertProblem(get("/api/admin/probe", elevatedClaim), 403, "FORBIDDEN");
        context.getBean(JdbcTemplate.class).update("UPDATE users SET role = 'TEACHER' WHERE id = ?", account.id());
        assertThat(get("/api/teacher/probe", token).statusCode()).isEqualTo(200);
        assertProblem(get("/api/admin/probe", token), 403, "FORBIDDEN");
        assertThat(JSON.readTree(get("/api/users/me", token).body()).get("role").asText()).isEqualTo("TEACHER");
        context.getBean(JdbcTemplate.class).update("UPDATE users SET enabled = false WHERE id = ?", account.id());
        assertProblem(get("/api/users/me", token), 401, "UNAUTHORIZED");
        assertProblem(get("/api/teacher/probe", token), 401, "UNAUTHORIZED");
    }

    @Test
    void startupBootstrapAdministratorCanLoginAndAccessRoleProtectedRoutes() throws Exception {
        String token = login("initial-admin", ADMIN_PASSWORD);
        assertThat(get("/api/admin/probe", token).statusCode()).isEqualTo(200);
        assertThat(get("/api/teacher/probe", token).statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(get("/api/users/me", token).body()).get("role").asText()).isEqualTo("ADMIN");
    }

    @Test
    void healthAndDocumentationRemainPublicAndOpenApiDescribesBearerAuth() throws Exception {
        assertThat(get("/api/v1/health", null).statusCode()).isEqualTo(200);
        assertThat(get("/actuator/health", null).statusCode()).isEqualTo(200);
        HttpResponse<String> docs = get("/v3/api-docs", null);
        assertThat(docs.statusCode()).isEqualTo(200);
        JsonNode api = JSON.readTree(docs.body());
        assertThat(api.at("/components/securitySchemes/bearerAuth/scheme").asText()).isEqualTo("bearer");
        assertThat(api.at("/paths/~1api~1users~1me/get/security/0/bearerAuth").isArray()).isTrue();
        assertThat(api.at("/components/schemas/RegisterRequest/properties").has("role")).isFalse();
        assertProblem(get("/actuator/env", null), 401, "UNAUTHORIZED");
    }

    private static Account register() throws Exception {
        String name = name();
        HttpResponse<String> response = post("/api/auth/register", Map.of("username", name, "email", name + "@example.test", "password", PASSWORD));
        assertThat(response.statusCode()).isEqualTo(201);
        return new Account(name, UUID.fromString(JSON.readTree(response.body()).get("id").asText()));
    }

    private static String login(String identifier, String password) throws Exception {
        HttpResponse<String> response = post("/api/auth/login", Map.of("identifier", identifier, "password", password));
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body()).get("accessToken").asText();
    }

    private static HttpResponse<String> post(String path, Object payload) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload))).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(String path, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).GET();
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("content-type").orElse("")).startsWith("application/problem+json");
        JsonNode problem = JSON.readTree(response.body());
        assertThat(problem.get("status").asInt()).isEqualTo(status);
        assertThat(problem.get("code").asText()).isEqualTo(code);
        assertThat(response.body()).doesNotContain("passwordHash", "stackTrace", PASSWORD, ADMIN_PASSWORD);
        if (status == 401) assertThat(response.headers().firstValue("www-authenticate")).contains("Bearer");
    }

    private static String signed(JwtEncoder encoder, String subject, String issuer, String audience, Instant issuedAt, Instant expiry) {
        var claims = JwtClaimsSet.builder().subject(subject).issuer(issuer).audience(List.of(audience))
                .issuedAt(issuedAt).claim("role", "ADMIN").claim("token_version", 0L);
        if (expiry != null) claims.expiresAt(expiry);
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims.build())).getTokenValue();
    }

    private static String name() { return "student-" + UUID.randomUUID(); }
    private record Account(String name, UUID id) { }

}
