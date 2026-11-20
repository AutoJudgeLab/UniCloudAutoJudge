package org.university.judge.api.auth;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.university.judge.api.JudgeApiApplication;
import org.university.judge.api.support.TestJwtKeys;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class AuthRateLimitIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withPassword(UUID.randomUUID().toString());
    private static final String REDIS_PASSWORD = UUID.randomUUID().toString();
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8-alpine")
            .withCommand("redis-server", "--requirepass", REDIS_PASSWORD).withExposedPorts(6379);
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static ConfigurableApplicationContext context;
    private static StringRedisTemplate redis;
    private static String base;

    @BeforeAll static void start() {
        context = new SpringApplicationBuilder(JudgeApiApplication.class).run(TestJwtKeys.arguments(
                "--server.port=0", "--server.servlet.context-path=/judge", "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(), "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.data.redis.host=" + REDIS.getHost(), "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                "--spring.data.redis.password=" + REDIS_PASSWORD, "--security.rate-limit.enabled=true",
                "--submission.cleanup.enabled=false"));
        redis = context.getBean(StringRedisTemplate.class);
        base = "http://127.0.0.1:" + context.getEnvironment().getRequiredProperty("local.server.port") + "/judge";
    }

    @BeforeEach void resetCounters() {
        redis.delete(List.of("rate-limit:auth:login:127.0.0.1", "rate-limit:auth:register:127.0.0.1"));
    }
    @AfterAll static void stop() { if (context != null) context.close(); }

    @Test void normalRegistrationLoginAndProfileWorkWithProtectionEnabled() throws Exception {
        String name = "u" + UUID.randomUUID().toString().replace("-", "");
        String password = "Strong9!" + UUID.randomUUID();
        var registered = post("/api/auth/register", Map.of("username", name, "email", name + "@example.test", "password", password), null);
        assertThat(registered.statusCode()).as(registered.body()).isEqualTo(201);
        var login = post("/api/auth/login", Map.of("identifier", name, "password", password), null);
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        String token = JSON.readTree(login.body()).get("accessToken").asText();
        var profile = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/users/me"))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(profile.statusCode()).isEqualTo(200);
        assertThat(profile.body()).contains(name).doesNotContain(password, "passwordHash");
        assertThat(login.headers().firstValue("Set-Cookie")).isEmpty();
    }

    @Test void endpointsHaveIndependentLimitsAndDoNotTrustSpoofedForwardingHeaders() throws Exception {
        for (int i = 0; i < 5; i++) assertThat(post("/api/auth/login", Map.of(), "198.51.100." + i).statusCode()).isEqualTo(400);
        var limited = post("/api/auth/login", Map.of(), "203.0.113.12");
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(JSON.readTree(limited.body()).get("code").asText()).isEqualTo("RATE_LIMIT_EXCEEDED");
        assertThat(Integer.parseInt(limited.headers().firstValue("Retry-After").orElseThrow())).isBetween(1, 60);
        for (int i = 0; i < 3; i++) assertThat(post("/api/auth/register", Map.of(), null).statusCode()).isEqualTo(400);
        assertThat(post("/api/auth/register", Map.of(), null).statusCode()).isEqualTo(429);
        assertThat(HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
    }

    @Test void concurrentRequestsShareAnAtomicBudget() throws Exception {
        try (var pool = Executors.newFixedThreadPool(12)) {
            var barrier = new CyclicBarrier(12);
            var tasks = new ArrayList<Callable<Integer>>();
            for (int i = 0; i < 12; i++) tasks.add(() -> {
                barrier.await(10, TimeUnit.SECONDS); return post("/api/auth/login", Map.of(), null).statusCode();
            });
            var statuses = new ArrayList<Integer>();
            for (var future : pool.invokeAll(tasks)) statuses.add(future.get(10, TimeUnit.SECONDS));
            assertThat(statuses.stream().filter(status -> status == 400).count()).isEqualTo(5);
            assertThat(statuses.stream().filter(status -> status == 429).count()).isEqualTo(7);
        }
    }

    @Test void anExpiredWindowAllowsLoginRequestsAgain() throws Exception {
        for (int i = 0; i < 6; i++) post("/api/auth/login", Map.of(), null);
        String key = "rate-limit:auth:login:127.0.0.1";
        assertThat(redis.getExpire(key)).isBetween(1L, 60L);
        redis.expire(key, Duration.ofMillis(1));
        Thread.sleep(30);
        assertThat(post("/api/auth/login", Map.of(), null).statusCode()).isEqualTo(400);
    }

    private static HttpResponse<String> post(String path, Object body, String forwardedIp) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json");
        if (forwardedIp != null) request.header("X-Forwarded-For", forwardedIp);
        return HTTP.send(request.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
