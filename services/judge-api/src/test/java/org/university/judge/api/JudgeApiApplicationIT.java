package org.university.judge.api;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.university.judge.api.support.TestJwtKeys;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class JudgeApiApplicationIT {
    private static final String REDIS_PASSWORD = UUID.randomUUID().toString();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withPassword(UUID.randomUUID().toString());

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8-alpine"))
            .withExposedPorts(6379)
            .withCommand("redis-server", "--requirepass", REDIS_PASSWORD);

    private static ConfigurableApplicationContext context;
    private static RestClient client;

    // Use the actual application without SpringExtension, which requires JUnit 6.
    @BeforeAll
    static void startApplication() {
        context = new SpringApplicationBuilder(JudgeApiApplication.class).run(TestJwtKeys.arguments(
                "--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.data.redis.host=" + REDIS.getHost(),
                "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                "--spring.data.redis.password=" + REDIS_PASSWORD));
        client = RestClient.create("http://localhost:"
                + context.getEnvironment().getRequiredProperty("local.server.port"));
    }

    @AfterAll
    static void closeApplication() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void applicationStartsWithMigratedPostgresAndReachableRedis() {
        assertThat(context.getBean(org.university.judge.storage.ObjectStorageService.class)).isNotNull();
        Flyway flyway = context.getBean(Flyway.class);
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("12");
        assertThat(flyway.info().pending()).isEmpty();
        StringRedisTemplate redis = context.getBean(StringRedisTemplate.class);
        assertThat(redis.execute((RedisCallback<String>) connection -> connection.ping())).isEqualTo("PONG");
        assertThat(get("/api/v1/health")).isEqualTo("{\"status\":\"UP\"}");
        assertThat(get("/actuator/health")).contains("\"status\":\"UP\"")
                .doesNotContain("components", "details");
        assertThat(get("/actuator/health/readiness")).contains("\"status\":\"UP\"");
    }

    @Test
    void openApiAndSwaggerUiAreAvailable() {
        assertThat(get("/v3/api-docs")).contains("/api/v1/health", "Check API availability");
        assertThat(get("/swagger-ui/index.html")).contains("Swagger UI");
    }

    private String get(String path) {
        return client.get().uri(path).retrieve().body(String.class);
    }
}
