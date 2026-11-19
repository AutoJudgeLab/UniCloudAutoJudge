package org.university.judge.worker;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.university.judge.worker.config.WorkerProperties;
import org.university.judge.worker.model.JudgeJob;
import org.university.judge.worker.model.TestCase;

/** Shared test data. No Spring context is needed anywhere in the unit tests. */
public final class Fixtures {

    private Fixtures() { }

    public static WorkerProperties properties() {
        return properties("docker", "http://localhost:1", List.of("JAVA", "PYTHON", "C", "CPP"));
    }

    public static WorkerProperties properties(String dockerBinary, String apiBaseUrl, List<String> languages) {
        return new WorkerProperties("judge:submissions:v1", "judge:submissions:dead:v1", "judge-workers", "test-worker",
                2, 3, Duration.ofMillis(100), Duration.ofMinutes(5), Duration.ofSeconds(5),
                new WorkerProperties.Api(apiBaseUrl, "test-token", Duration.ofSeconds(2), Duration.ofSeconds(5)),
                new WorkerProperties.Limits(Duration.ofSeconds(2), 256, Duration.ofSeconds(30), 512, 1_048_576, 16_384),
                new WorkerProperties.Sandbox(dockerBinary, 1.0, 128, 64, Duration.ofSeconds(3)),
                new WorkerProperties.Languages(languages, "eclipse-temurin:21-jdk", "python:3.13-slim", "gcc:14"));
    }

    public static TestCase test(int order, String input, String expected, String points) {
        return new TestCase(UUID.nameUUIDFromBytes(("test-" + order).getBytes()), input, expected, new BigDecimal(points), order);
    }

    public static JudgeJob job(String language, String fileName, String source, TestCase... tests) {
        return new JudgeJob(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), language, fileName, source, null, null,
                List.of(tests));
    }
}
