package org.university.judge.api.user.administration;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.university.judge.api.JudgeApiApplication;
import org.university.judge.api.security.AuthenticatedUser;
import org.university.judge.api.support.TestJwtKeys;
import org.university.judge.api.user.Role;
import org.university.judge.api.user.User;
import org.university.judge.api.user.UserRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class AdminUserManagementIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withPassword(UUID.randomUUID().toString());
    private static final String PASSWORD = "Management9!" + UUID.randomUUID();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static ConfigurableApplicationContext context;
    private static UserRepository users;
    private static JdbcTemplate jdbc;
    private static String base;
    private static String passwordHash;
    private static UUID rootId;
    private String adminToken;

    @BeforeAll
    static void startApplication() {
        context = new SpringApplicationBuilder(JudgeApiApplication.class).run(TestJwtKeys.arguments(
                "--server.port=0", "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(), "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.data.redis.password=unused", "--management.health.redis.enabled=false",
                "--management.endpoint.health.group.readiness.include=readinessState,db",
                "--security.bootstrap.enabled=true", "--security.bootstrap.username=management-admin",
                "--security.bootstrap.email=management-admin@example.test", "--security.bootstrap.password=" + PASSWORD));
        users = context.getBean(UserRepository.class);
        jdbc = context.getBean(JdbcTemplate.class);
        base = "http://localhost:" + context.getEnvironment().getRequiredProperty("local.server.port");
        rootId = users.findByUsername("management-admin").orElseThrow().getId();
        passwordHash = context.getBean(PasswordEncoder.class).encode(PASSWORD);
    }

    @BeforeEach
    void restoreIsolatedTestAdministrator() throws Exception {
        // All data lives in this test's disposable container. Keep tests independent
        // when a preceding scenario deliberately disabled one of its administrators.
        jdbc.update("UPDATE users SET enabled = true WHERE id = ?", rootId);
        jdbc.update("UPDATE users SET enabled = false WHERE role = 'ADMIN' AND id <> ?", rootId);
        adminToken = login("management-admin");
    }

    @AfterAll
    static void closeApplication() {
        if (context != null) context.close();
    }

    @Test
    void everyEndpointRejectsMissingAuthenticationAndNonAdministrators() throws Exception {
        User student = account(unique("student"), Role.STUDENT, true);
        User teacher = account(unique("teacher"), Role.TEACHER, true);
        List<Route> routes = List.of(new Route("GET", "/api/admin/users", null),
                new Route("GET", "/api/admin/users/" + student.getId(), null),
                new Route("PATCH", "/api/admin/users/" + student.getId() + "/role", Map.of("role", "TEACHER")),
                new Route("PATCH", "/api/admin/users/" + student.getId() + "/status", Map.of("enabled", false)));
        for (Route route : routes) {
            problem(send(route.method(), route.path(), null, route.body()), 401, "UNAUTHORIZED");
        }
        for (String token : List.of(login(student.getUsername()), login(teacher.getUsername()))) {
            for (Route route : routes) problem(send(route.method(), route.path(), token, route.body()), 403, "FORBIDDEN");
        }
        assertThat(users.findById(student.getId()).orElseThrow().getRole()).isEqualTo(Role.STUDENT);
        assertThat(users.findById(student.getId()).orElseThrow().isEnabled()).isTrue();
        withIdentity(new AuthenticatedUser(student.getId(), Role.STUDENT, student.getTokenVersion()), () ->
                assertThatThrownBy(() -> context.getBean(AdminUserService.class).get(rootId)).isInstanceOf(AccessDeniedException.class));
    }

    @Test
    void listAndGetReturnOnlyDocumentedProfileFields() throws Exception {
        User target = account(unique("profile"), Role.STUDENT, true);
        HttpResponse<String> response = get("/api/admin/users/" + target.getId(), adminToken);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode profile = JSON.readTree(response.body());
        assertProfile(profile);
        assertThat(profile.get("id").asText()).isEqualTo(target.getId().toString());
        assertThat(profile.get("createdAt").asText()).isNotBlank();
        HttpResponse<String> listing = get("/api/admin/users", adminToken);
        assertThat(listing.statusCode()).isEqualTo(200);
        JsonNode page = JSON.readTree(listing.body());
        assertThat(page.get("page").asInt()).isZero();
        assertThat(page.get("size").asInt()).isEqualTo(20);
        assertThat(page.get("totalElements").asLong()).isEqualTo(users.count());
        for (JsonNode user : page.get("content")) assertProfile(user);
        assertThat(listing.body()).doesNotContain(passwordHash, PASSWORD);
    }

    @Test
    void searchFiltersPaginationAndSortingComposeCorrectly() throws Exception {
        String prefix = unique("batch");
        account(prefix + "-alpha", Role.STUDENT, true);
        User beta = account(prefix + "-beta", Role.TEACHER, true);
        User gamma = account(prefix + "-gamma", Role.TEACHER, false);
        String filter = "/api/admin/users?username=" + prefix.toUpperCase() + "&sort=username,asc&size=2";
        JsonNode first = body(get(filter, adminToken), 200);
        assertThat(first.get("totalElements").asLong()).isEqualTo(3);
        assertThat(first.get("totalPages").asInt()).isEqualTo(2);
        assertThat(first.at("/content/0/username").asText()).isEqualTo(prefix + "-alpha");
        assertThat(first.at("/content/1/username").asText()).isEqualTo(beta.getUsername());
        JsonNode second = body(get(filter + "&page=1", adminToken), 200);
        assertThat(second.get("content").size()).isEqualTo(1);
        assertThat(second.at("/content/0/id").asText()).isEqualTo(gamma.getId().toString());
        assertThat(body(get(filter + "&page=50", adminToken), 200).get("content").size()).isZero();
        JsonNode descending = body(get("/api/admin/users?username=" + prefix + "&sort=username,desc", adminToken), 200);
        assertThat(descending.at("/content/0/username").asText()).isEqualTo(gamma.getUsername());
        JsonNode combined = body(get("/api/admin/users?username=" + prefix
                + "&email=GAMMA%40EXAMPLE.TEST&role=TEACHER&enabled=false", adminToken), 200);
        assertThat(combined.get("totalElements").asLong()).isEqualTo(1);
        assertThat(combined.at("/content/0/id").asText()).isEqualTo(gamma.getId().toString());
        assertThat(body(get("/api/admin/users?username=" + prefix + "&role=TEACHER&enabled=true", adminToken), 200)
                .at("/content/0/id").asText()).isEqualTo(beta.getId().toString());
        for (String field : List.of("id", "email", "role", "enabled", "createdAt")) {
            assertThat(get("/api/admin/users?username=" + prefix + "&sort=" + field + ",asc", adminToken).statusCode()).isEqualTo(200);
        }
    }

    @Test
    void searchTreatsWildcardsLiterallyAndPaginationHasStableTies() throws Exception {
        String prefix = unique("literal");
        User literal = account(prefix + "_one", Role.STUDENT, true);
        account(prefix + "xone", Role.STUDENT, true);
        JsonNode result = body(get("/api/admin/users?username=" + encode(prefix + "_"), adminToken), 200);
        assertThat(result.get("totalElements").asInt()).isEqualTo(1);
        assertThat(result.at("/content/0/id").asText()).isEqualTo(literal.getId().toString());
        assertThat(body(get("/api/admin/users?username=" + encode(prefix + "%"), adminToken), 200)
                .get("totalElements").asInt()).isZero();
        JsonNode page0 = body(get("/api/admin/users?username=" + prefix + "&sort=role,asc&size=1", adminToken), 200);
        JsonNode page1 = body(get("/api/admin/users?username=" + prefix + "&sort=role,asc&size=1&page=1", adminToken), 200);
        assertThat(page0.at("/content/0/id").asText()).isNotEqualTo(page1.at("/content/0/id").asText());
        assertThat(body(get("/api/admin/users?username=" + prefix + "&sort=role,asc&size=1", adminToken), 200))
                .isEqualTo(page0);
    }

    @Test
    void invalidFiltersSortsIdsAndPatchBodiesReturnClientErrors() throws Exception {
        for (String query : List.of("page=-1", "size=0", "size=101", "sort=passwordHash,asc", "sort=username,sideways",
                "role=ROOT", "enabled=unknown", "page=not-a-number")) {
            HttpResponse<String> response = get("/api/admin/users?" + query, adminToken);
            assertThat(response.statusCode()).as(query).isEqualTo(400);
            assertThat(response.headers().firstValue("content-type").orElse("")).contains("application/problem+json");
        }
        problem(get("/api/admin/users/not-a-uuid", adminToken), 400, "INVALID_REQUEST");
        UUID missing = UUID.randomUUID();
        problem(get("/api/admin/users/" + missing, adminToken), 404, "USER_NOT_FOUND");
        problem(patch(missing, "role", Map.of("role", "TEACHER"), adminToken), 404, "USER_NOT_FOUND");
        problem(patch(missing, "status", Map.of("enabled", true), adminToken), 404, "USER_NOT_FOUND");
        User target = account(unique("invalid"), Role.STUDENT, true);
        for (Map<String, ?> payload : List.<Map<String, ?>>of(Map.of(), Map.of("role", "ROOT"), Map.of("role", 0),
                Map.of("role", "TEACHER", "enabled", false))) {
            assertThat(patch(target.getId(), "role", payload, adminToken).statusCode()).isEqualTo(400);
        }
        for (Map<String, ?> payload : List.<Map<String, ?>>of(Map.of(), Map.of("enabled", "invalid"),
                Map.of("enabled", false, "role", "ADMIN"))) {
            assertThat(patch(target.getId(), "status", payload, adminToken).statusCode()).isEqualTo(400);
        }
        assertThat(users.findById(target.getId()).orElseThrow().isEnabled()).isTrue();
    }

    @Test
    void administratorCanPromoteAndDemoteWithoutChangingOtherAccountFields() throws Exception {
        User target = account(unique("roles"), Role.STUDENT, true);
        String token = login(target.getUsername());
        JsonNode promoted = body(patch(target.getId(), "role", Map.of("role", "TEACHER"), adminToken), 200);
        assertProfile(promoted);
        assertThat(promoted.get("role").asText()).isEqualTo("TEACHER");
        assertThat(body(get("/api/users/me", token), 200).get("role").asText()).isEqualTo("TEACHER");
        assertThat(patch(target.getId(), "role", Map.of("role", "TEACHER"), adminToken).statusCode()).isEqualTo(200);
        assertThat(body(patch(target.getId(), "role", Map.of("role", "STUDENT"), adminToken), 200).get("role").asText()).isEqualTo("STUDENT");
        User saved = users.findById(target.getId()).orElseThrow();
        assertThat(saved.isEnabled()).isTrue();
        assertThat(saved.getEmail()).isEqualTo(target.getEmail());
        assertThat(saved.getPasswordHash()).isEqualTo(passwordHash);
        assertThat(saved.getCreatedAt()).isEqualTo(target.getCreatedAt());
        assertThat(saved.getTokenVersion()).isEqualTo(target.getTokenVersion());
    }

    @Test
    void generalRoleEndpointCannotProvisionOrDemoteAdminsOrChangeOwnRole() throws Exception {
        User target = account(unique("restricted"), Role.STUDENT, true);
        User otherAdmin = account(unique("protected-admin"), Role.ADMIN, true);
        problem(patch(target.getId(), "role", Map.of("role", "ADMIN"), adminToken), 400, "INVALID_ROLE_CHANGE");
        problem(patch(otherAdmin.getId(), "role", Map.of("role", "TEACHER"), adminToken), 409, "ADMIN_ROLE_PROTECTED");
        problem(patch(rootId, "role", Map.of("role", "STUDENT"), adminToken), 403, "FORBIDDEN");
        assertThat(users.findById(otherAdmin.getId()).orElseThrow().getRole()).isEqualTo(Role.ADMIN);
        assertThat(users.findById(target.getId()).orElseThrow().getRole()).isEqualTo(Role.STUDENT);
    }

    @Test
    void statusChangesInvalidateTokensPermanentlyAndNoOpStatusChangesDoNot() throws Exception {
        User target = account(unique("status"), Role.STUDENT, true);
        String oldToken = login(target.getUsername());
        assertThat(patch(target.getId(), "status", Map.of("enabled", true), adminToken).statusCode()).isEqualTo(200);
        assertThat(get("/api/users/me", oldToken).statusCode()).isEqualTo(200);
        JsonNode disabled = body(patch(target.getId(), "status", Map.of("enabled", false), adminToken), 200);
        assertProfile(disabled);
        assertThat(disabled.get("enabled").asBoolean()).isFalse();
        long disabledVersion = users.findById(target.getId()).orElseThrow().getTokenVersion();
        assertThat(disabledVersion).isEqualTo(target.getTokenVersion() + 1);
        problem(get("/api/users/me", oldToken), 401, "UNAUTHORIZED");
        problem(send("POST", "/api/auth/login", null, Map.of("identifier", target.getUsername(), "password", PASSWORD)), 401, "UNAUTHORIZED");
        assertThat(patch(target.getId(), "status", Map.of("enabled", false), adminToken).statusCode()).isEqualTo(200);
        assertThat(users.findById(target.getId()).orElseThrow().getTokenVersion()).isEqualTo(disabledVersion);
        assertThat(body(patch(target.getId(), "status", Map.of("enabled", true), adminToken), 200).get("enabled").asBoolean()).isTrue();
        assertThat(users.findById(target.getId()).orElseThrow().getTokenVersion()).isEqualTo(disabledVersion + 1);
        problem(get("/api/users/me", oldToken), 401, "UNAUTHORIZED");
        assertThat(get("/api/users/me", login(target.getUsername())).statusCode()).isEqualTo(200);
    }

    @Test
    void lastActiveAdministratorCannotBeDisabledAndRemainsAuthenticated() throws Exception {
        assertThat(users.countByRoleAndEnabledTrue(Role.ADMIN)).isEqualTo(1);
        long version = users.findById(rootId).orElseThrow().getTokenVersion();
        problem(patch(rootId, "status", Map.of("enabled", false), adminToken), 409, "LAST_ACTIVE_ADMIN");
        assertThat(users.findById(rootId).orElseThrow().isEnabled()).isTrue();
        assertThat(users.findById(rootId).orElseThrow().getTokenVersion()).isEqualTo(version);
        assertThat(get("/api/admin/users", adminToken).statusCode()).isEqualTo(200);
    }

    @Test
    void simultaneousRoleAndStatusChangesPreserveBothUpdates() throws Exception {
        User target = account(unique("concurrent"), Role.STUDENT, true);
        List<HttpResponse<String>> responses = concurrently(
                () -> patch(target.getId(), "role", Map.of("role", "TEACHER"), adminToken),
                () -> patch(target.getId(), "status", Map.of("enabled", false), adminToken));
        assertThat(responses).extracting(HttpResponse::statusCode).containsOnly(200);
        User saved = users.findById(target.getId()).orElseThrow();
        assertThat(saved.getRole()).isEqualTo(Role.TEACHER);
        assertThat(saved.isEnabled()).isFalse();
        assertThat(saved.getTokenVersion()).isEqualTo(target.getTokenVersion() + 1);
    }

    @Test
    void concurrentAdministratorsCannotDisableAllAdministrators() throws Exception {
        User second = account(unique("second-admin"), Role.ADMIN, true);
        String secondToken = login(second.getUsername());
        List<HttpResponse<String>> responses = concurrently(
                () -> patch(rootId, "status", Map.of("enabled", false), adminToken),
                () -> patch(second.getId(), "status", Map.of("enabled", false), secondToken));
        assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 409);
        assertThat(users.countByRoleAndEnabledTrue(Role.ADMIN)).isEqualTo(1);
        User root = users.findById(rootId).orElseThrow();
        String disabledToken = root.isEnabled() ? secondToken : adminToken;
        problem(get("/api/admin/users", disabledToken), 401, "UNAUTHORIZED");
    }

    @Test
    void mutationRechecksActorAfterAuthenticationBeforePerformingWrites() throws Exception {
        User actor = account(unique("stale-admin"), Role.ADMIN, true);
        User target = account(unique("unchanged"), Role.STUDENT, true);
        AuthenticatedUser oldIdentity = new AuthenticatedUser(actor.getId(), Role.ADMIN, actor.getTokenVersion());
        assertThat(patch(actor.getId(), "status", Map.of("enabled", false), adminToken).statusCode()).isEqualTo(200);
        assertThat(patch(actor.getId(), "status", Map.of("enabled", true), adminToken).statusCode()).isEqualTo(200);
        withIdentity(oldIdentity, () -> assertThatThrownBy(() -> context.getBean(AdminUserService.class)
                .changeRole(target.getId(), Role.TEACHER)).isInstanceOf(BadCredentialsException.class));
        long version = users.findById(actor.getId()).orElseThrow().getTokenVersion();
        jdbc.update("UPDATE users SET role = 'TEACHER' WHERE id = ?", actor.getId());
        withIdentity(new AuthenticatedUser(actor.getId(), Role.ADMIN, version), () -> assertThatThrownBy(() ->
                context.getBean(AdminUserService.class).changeStatus(target.getId(), false)).isInstanceOf(AccessDeniedException.class));
        assertThat(users.findById(target.getId()).orElseThrow().getRole()).isEqualTo(Role.STUDENT);
        assertThat(users.findById(target.getId()).orElseThrow().isEnabled()).isTrue();
    }

    @Test
    void missingOrMalformedTokenVersionsAreRejectedAndDatabaseVersionsCannotRewind() throws Exception {
        User target = account(unique("token-version"), Role.STUDENT, true);
        JwtEncoder encoder = context.getBean(JwtEncoder.class);
        for (Object version : List.of("missing", "0", 0.5, -1L, 1L)) {
            var claims = JwtClaimsSet.builder().issuer("online-judge").audience(List.of("judge-api"))
                    .subject(target.getId().toString()).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300));
            if (!version.equals("missing")) claims.claim("token_version", version);
            String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims.build())).getTokenValue();
            problem(get("/api/users/me", token), 401, "UNAUTHORIZED");
        }
        jdbc.update("UPDATE users SET enabled = false WHERE id = ?", target.getId());
        assertThat(users.findById(target.getId()).orElseThrow().getTokenVersion()).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("UPDATE users SET token_version = 0 WHERE id = ?", target.getId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void swaggerDocumentsAllFourAdministratorOperations() throws Exception {
        JsonNode docs = body(get("/v3/api-docs", null), 200);
        for (String operation : List.of("/~1api~1admin~1users/get", "/~1api~1admin~1users~1{userId}/get",
                "/~1api~1admin~1users~1{userId}~1role/patch", "/~1api~1admin~1users~1{userId}~1status/patch")) {
            assertThat(docs.at("/paths" + operation + "/security/0/bearerAuth").isArray()).isTrue();
        }
        List<String> parameters = new ArrayList<>();
        for (JsonNode parameter : docs.at("/paths/~1api~1admin~1users/get/parameters")) parameters.add(parameter.get("name").asText());
        assertThat(parameters).contains("page", "size", "sort", "username", "email", "role", "enabled");
        assertThat(docs.at("/components/schemas/UserResponse/properties").has("passwordHash")).isFalse();
        assertThat(docs.at("/components/schemas/UserResponse/properties").has("tokenVersion")).isFalse();
    }

    private static User account(String name, Role role, boolean enabled) {
        User user = new User(name, name + "@example.test", passwordHash, role);
        user.setEnabled(enabled);
        return users.saveAndFlush(user);
    }

    private static String unique(String prefix) { return prefix + "-" + UUID.randomUUID().toString().substring(0, 12); }
    private static String encode(String text) { return URLEncoder.encode(text, StandardCharsets.UTF_8); }

    private static String login(String name) throws Exception {
        return body(send("POST", "/api/auth/login", null, Map.of("identifier", name, "password", PASSWORD)), 200)
                .get("accessToken").asText();
    }

    private static HttpResponse<String> get(String path, String token) throws Exception { return send("GET", path, token, null); }

    private static HttpResponse<String> patch(UUID id, String field, Object payload, String token) throws Exception {
        return send("PATCH", "/api/admin/users/" + id + "/" + field, token, payload);
    }

    private static HttpResponse<String> send(String method, String path, String token, Object payload) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (payload != null) request.header("Content-Type", "application/json");
        request.method(method, payload == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload)));
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode body(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return JSON.readTree(response.body());
    }

    private static void problem(HttpResponse<String> response, int status, String code) {
        JsonNode problem = body(response, status);
        assertThat(response.headers().firstValue("content-type").orElse("")).startsWith("application/problem+json");
        assertThat(problem.get("code").asText()).isEqualTo(code);
        assertThat(response.body()).doesNotContain(passwordHash, PASSWORD);
    }

    private static void assertProfile(JsonNode node) {
        assertThat(node.size()).isEqualTo(6);
        for (String field : List.of("id", "username", "email", "role", "enabled", "createdAt")) assertThat(node.has(field)).isTrue();
    }

    private static List<HttpResponse<String>> concurrently(Callable<HttpResponse<String>> first, Callable<HttpResponse<String>> second)
            throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return first.call(); });
            var b = executor.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return second.call(); });
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
    }

    private static void withIdentity(AuthenticatedUser identity, Runnable action) {
        var previous = SecurityContextHolder.getContext();
        var security = SecurityContextHolder.createEmptyContext();
        security.setAuthentication(new PreAuthenticatedAuthenticationToken(identity, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + identity.role().name()))));
        SecurityContextHolder.setContext(security);
        try { action.run(); } finally { SecurityContextHolder.setContext(previous); }
    }

    private record Route(String method, String path, Object body) { }
}
