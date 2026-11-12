package org.university.judge.api.support;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;

public final class TestJwtKeys {
    public static final KeyPair KEYS = generate();

    private TestJwtKeys() {
    }

    public static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static String[] arguments(String... existing) {
        var arguments = new ArrayList<>(Arrays.asList(existing));
        // Most suites do not call storage; submission tests supply a real endpoint.
        for (var entry : java.util.Map.of("endpoint", "http://127.0.0.1:1", "access-key", java.util.UUID.randomUUID().toString(),
                "secret-key", java.util.UUID.randomUUID().toString(), "bucket", "integration-test").entrySet()) {
            String prefix = "--storage.s3." + entry.getKey() + "=";
            if (arguments.stream().noneMatch(argument -> argument.startsWith(prefix))) arguments.add(prefix + entry.getValue());
        }
        for (var entry : java.util.Map.of("outbox.enabled", "false", "management.health.s3.enabled", "false",
                "management.endpoint.health.group.readiness.include", "readinessState,db",
                "management.endpoint.health.group.dependencies.include", "db").entrySet()) {
            String prefix = "--" + entry.getKey() + "=";
            if (arguments.stream().noneMatch(argument -> argument.startsWith(prefix))) arguments.add(prefix + entry.getValue());
        }
        arguments.add("--security.jwt.private-key-base64=" + Base64.getEncoder().encodeToString(KEYS.getPrivate().getEncoded()));
        arguments.add("--security.jwt.public-key-base64=" + Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded()));
        arguments.add("--security.jwt.issuer=online-judge");
        arguments.add("--security.jwt.audience=judge-api");
        arguments.add("--security.jwt.access-token-ttl=15m");
        if (arguments.stream().noneMatch(argument -> argument.startsWith("--security.bootstrap.enabled="))) {
            arguments.add("--security.bootstrap.enabled=false");
        }
        return arguments.toArray(String[]::new);
    }
}
