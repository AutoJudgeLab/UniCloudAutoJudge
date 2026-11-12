package org.university.judge.api.auth.bootstrap;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.university.judge.api.JudgeApiApplication;
import org.university.judge.api.support.TestJwtKeys;
import org.university.judge.api.user.Role;
import org.university.judge.api.user.User;
import org.university.judge.api.user.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class AdminBootstrapIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withPassword(UUID.randomUUID().toString());
    private ConfigurableApplicationContext context;
    private AdminBootstrapSettings settings;
    private AdminBootstrapService bootstrap;
    private UserRepository users;
    private JdbcTemplate jdbc;
    private String password;

    @BeforeEach
    void startFreshDatabaseSchema() {
        String schema = "bootstrap_" + UUID.randomUUID().toString().replace("-", "");
        String url = POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema;
        context = new SpringApplicationBuilder(JudgeApiApplication.class).run(TestJwtKeys.arguments(
                "--server.port=0", "--spring.datasource.url=" + url,
                "--spring.datasource.username=" + POSTGRES.getUsername(), "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.flyway.schemas=" + schema, "--spring.flyway.default-schema=" + schema,
                "--spring.data.redis.password=unused", "--management.health.redis.enabled=false",
                "--management.endpoint.health.group.readiness.include=readinessState,db", "--security.bootstrap.enabled=false"));
        settings = context.getBean(AdminBootstrapSettings.class);
        bootstrap = context.getBean(AdminBootstrapService.class);
        users = context.getBean(UserRepository.class);
        jdbc = context.getBean(JdbcTemplate.class);
        password = "Bootstrap9!" + UUID.randomUUID();
    }

    @AfterEach
    void closeApplication() {
        if (context != null) context.close();
    }

    @Test
    void disabledBootstrapAndInvalidConfigurationNeverCreateOrPromoteAnAccount() {
        bootstrap.provisionIfEnabled();
        assertThat(users.count()).isZero();
        settings.setEnabled(true);
        assertThatThrownBy(bootstrap::provisionIfEnabled).isInstanceOf(IllegalStateException.class);
        assertThat(users.count()).isZero();
        configure();
        settings.setPassword("weak");
        assertThatThrownBy(bootstrap::provisionIfEnabled).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("weak");
        settings.setPassword(password);
        User student = users.saveAndFlush(new User("initial-admin", "student@example.test", "test-only-hash", Role.STUDENT));
        assertThatThrownBy(bootstrap::provisionIfEnabled).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("never promoted");
        assertThat(users.findById(student.getId()).orElseThrow().getRole()).isEqualTo(Role.STUDENT);
        assertThat(users.existsByRole(Role.ADMIN)).isFalse();
    }

    @Test
    void concurrentBootstrapCreatesOneAdministratorAndNeverResetsTheirPassword() throws Exception {
        configure();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = executor.invokeAll(List.<Callable<Void>>of(
                    () -> { bootstrap.provisionIfEnabled(); return null; },
                    () -> { bootstrap.provisionIfEnabled(); return null; }));
            for (var future : futures) future.get(15, TimeUnit.SECONDS);
        }
        assertThat(users.countByRoleAndEnabledTrue(Role.ADMIN)).isEqualTo(1);
        User admin = users.findByUsername("initial-admin").orElseThrow();
        String hash = admin.getPasswordHash();
        assertThat(context.getBean(PasswordEncoder.class).matches(password, hash)).isTrue();
        settings.setPassword("Different9!" + UUID.randomUUID());
        settings.setUsername("another-admin");
        bootstrap.provisionIfEnabled();
        assertThat(users.findById(admin.getId()).orElseThrow().getPasswordHash()).isEqualTo(hash);
        assertThat(users.findByUsername("another-admin")).isEmpty();
        settings.setEnabled(false);
        settings.setPassword(null);
        bootstrap.provisionIfEnabled();
        assertThat(users.countByRoleAndEnabledTrue(Role.ADMIN)).isEqualTo(1);
    }

    @Test
    void lastAdministratorSurvivesRemovalDemotionDisablingAndConcurrentChanges() throws Exception {
        configure();
        bootstrap.provisionIfEnabled();
        UUID first = users.findByUsername("initial-admin").orElseThrow().getId();
        for (String statement : List.of("DELETE FROM users WHERE id = ?",
                "UPDATE users SET role = 'TEACHER' WHERE id = ?", "UPDATE users SET enabled = false WHERE id = ?")) {
            assertThatThrownBy(() -> jdbc.update(statement, first)).isInstanceOf(DataAccessException.class);
        }
        User second = users.saveAndFlush(new User("second-admin", "second-admin@example.test", "test-only-hash", Role.ADMIN));
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> disableConcurrently(first, barrier));
            var b = executor.submit(() -> disableConcurrently(second.getId(), barrier));
            assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(users.countByRoleAndEnabledTrue(Role.ADMIN)).isEqualTo(1);
    }

    private boolean disableConcurrently(UUID id, CyclicBarrier barrier) throws Exception {
        try (Connection connection = context.getBean(DataSource.class).getConnection()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try (var statement = connection.createStatement()) {
                statement.execute("SET LOCAL statement_timeout = '10s'");
                // Both transactions deliberately acquire an old snapshot.
                statement.executeQuery("SELECT count(*) FROM users").close();
                barrier.await(5, TimeUnit.SECONDS);
                try (var update = connection.prepareStatement("UPDATE users SET enabled = false WHERE id = ?")) {
                    update.setObject(1, id);
                    update.executeUpdate();
                    connection.commit();
                    return true;
                } catch (SQLException expected) {
                    connection.rollback();
                    assertThat(expected.getSQLState()).isIn("23514", "40001", "40P01");
                    return false;
                }
            }
        }
    }

    private void configure() {
        settings.setEnabled(true);
        settings.setUsername("initial-admin");
        settings.setEmail("initial-admin@example.test");
        settings.setPassword(password);
    }
}
