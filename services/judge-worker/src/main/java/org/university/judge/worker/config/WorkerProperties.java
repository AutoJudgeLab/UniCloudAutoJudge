package org.university.judge.worker.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** All worker settings. Values come from environment variables; no secret has a default. */
@ConfigurationProperties(prefix = "judge.worker")
public record WorkerProperties(
        String stream,
        String deadLetterStream,
        String group,
        String consumerName,
        int concurrency,
        int maxDeliveries,
        Duration blockTimeout,
        Duration reclaimIdle,
        Duration shutdownGrace,
        Api api,
        Limits limits,
        Sandbox sandbox,
        Languages languages) {

    public WorkerProperties {
        if (concurrency < 1) throw new IllegalArgumentException("judge.worker.concurrency must be at least 1");
        if (maxDeliveries < 1) throw new IllegalArgumentException("judge.worker.max-deliveries must be at least 1");
    }

    public record Api(String baseUrl, String token, Duration connectTimeout, Duration requestTimeout) {
        public Api {
            if (token == null || token.isBlank()) throw new IllegalArgumentException("JUDGE_WORKER_TOKEN is required");
            if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("JUDGE_API_BASE_URL is required");
            if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        @Override public String toString() { return "Api[baseUrl=" + baseUrl + ", token=<redacted>]"; }
    }

    public record Limits(Duration timeLimit, int memoryMb, Duration compileTimeLimit, int compileMemoryMb,
            int maxOutputBytes, int storedOutputBytes) { }

    public record Sandbox(String dockerBinary, double cpus, int pidsLimit, int tmpfsMb, Duration startupGrace,
            @org.springframework.boot.context.properties.bind.DefaultValue("536870912") long workspaceBytes,
            @org.springframework.boot.context.properties.bind.DefaultValue("10000") int workspaceFiles) {
        public Sandbox(String dockerBinary, double cpus, int pidsLimit, int tmpfsMb, Duration startupGrace) {
            this(dockerBinary, cpus, pidsLimit, tmpfsMb, startupGrace, 536870912, 10000);
        }
        public Sandbox {
            if (workspaceBytes < 1048576 || workspaceFiles < 1) throw new IllegalArgumentException("Invalid workspace limits");
        }
    }

    public record Languages(List<String> enabled, String javaImage, String pythonImage, String gccImage,
            String nodeImage, String typescriptImage, String kotlinImage, String goImage, String rustImage,
            String csharpImage) { }
}
