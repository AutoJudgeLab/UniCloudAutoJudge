package org.university.judge.storage;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.errors.ErrorResponseException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import org.university.judge.storage.config.S3StorageConfiguration;

import static org.assertj.core.api.Assertions.*;
import static org.university.judge.storage.StorageException.Code.*;

@Testcontainers
class ObjectStorageIT {
    private static final Path ROOT = Path.of(System.getProperty("basedir", ".")).resolve("../..").normalize().toAbsolutePath();
    private static final String ROOT_USER = "root-" + UUID.randomUUID();
    private static final String ROOT_SECRET = UUID.randomUUID().toString();
    private static final String ACCESS = "app-" + UUID.randomUUID();
    private static final String SECRET = UUID.randomUUID().toString();
    private static final String BUCKET = "storage-integration";
    private static final Network NETWORK = Network.newNetwork();
    private static final ImageFromDockerfile SERVER_IMAGE = new ImageFromDockerfile("online-judge-minio:RELEASE.2025-10-15T17-29-55Z", false)
            .withDockerfile(ROOT.resolve("infrastructure/docker/minio/Dockerfile"));
    private static final ImageFromDockerfile CLIENT_IMAGE = new ImageFromDockerfile("online-judge-minio-client:RELEASE.2025-08-13T08-35-41Z", false)
            .withDockerfile(ROOT.resolve("infrastructure/docker/minio-client/Dockerfile"));

    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>(SERVER_IMAGE).withNetwork(NETWORK).withNetworkAliases("minio")
            .withEnv("MINIO_ROOT_USER", ROOT_USER).withEnv("MINIO_ROOT_PASSWORD", ROOT_SECRET)
            .withCommand("server", "/data", "--console-address", ":9001").withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000).withStartupTimeout(Duration.ofSeconds(60)));

    @BeforeAll
    static void initializePrivateBucket() {
        // Exercise the same script as Compose, including repeat initialization.
        for (int i = 0; i < 2; i++) {
            try (var initializer = new GenericContainer<>(CLIENT_IMAGE).withNetwork(NETWORK)
                    .withEnv("MINIO_ROOT_USER", ROOT_USER).withEnv("MINIO_ROOT_PASSWORD", ROOT_SECRET)
                    .withEnv("S3_ACCESS_KEY", ACCESS).withEnv("S3_SECRET_KEY", SECRET).withEnv("S3_BUCKET", BUCKET)
                    .withCopyFileToContainer(MountableFile.forHostPath(ROOT.resolve("infrastructure/docker/minio/init-storage.sh")), "/init-storage.sh")
                    .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("/bin/sh", "/init-storage.sh"))
                    .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofSeconds(30)))) {
                initializer.start();
                assertThat(initializer.getLogs()).contains("Private storage bucket").doesNotContain(ROOT_USER, ROOT_SECRET, ACCESS, SECRET);
            }
        }
    }

    @AfterAll
    static void closeNetwork() { NETWORK.close(); }

    private static String endpoint() { return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000); }
    private static ApplicationContextRunner context() {
        return new ApplicationContextRunner().withUserConfiguration(S3StorageConfiguration.class).withPropertyValues(
                "storage.s3.endpoint=" + endpoint(), "storage.s3.access-key=" + ACCESS, "storage.s3.secret-key=" + SECRET,
                "storage.s3.bucket=" + BUCKET, "storage.s3.max-upload-size=8MB", "storage.s3.request-timeout=3s");
    }

    @Test
    void uploadDownloadMetadataExistsAndDeleteUseGeneratedKeysAndPreserveUnicodeFilename() {
        context().run(context -> {
            var storage = context.getBean(ObjectStorageService.class);
            byte[] content = "// λ\nclass Main {}\r\n".getBytes(StandardCharsets.UTF_8);
            UUID submission = UUID.randomUUID();
            ObjectMetadata uploaded = storage.upload(new SourceUpload(submission, "Mäin.JAVA", content.length, new ByteArrayInputStream(content)));
            assertThat(uploaded.objectKey()).startsWith("submissions/" + submission + "/source/").endsWith(".java").doesNotContain("Mäin", "Main");
            ObjectKeys.validate(uploaded.objectKey());
            assertThat(storage.exists(uploaded.objectKey())).isTrue();
            assertThat(storage.getMetadata(uploaded.objectKey())).isEqualTo(uploaded);
            try (var download = storage.download(uploaded.objectKey())) {
                assertThat(download.content().readAllBytes()).isEqualTo(content);
                assertThat(download.metadata().originalFilename()).isEqualTo("Mäin.JAVA");
                assertThat(download.metadata().contentType()).isEqualTo("text/plain; charset=utf-8");
            }
            ObjectMetadata second = storage.upload(new SourceUpload(submission, "Mäin.JAVA", content.length, new ByteArrayInputStream(content)));
            assertThat(second.objectKey()).isNotEqualTo(uploaded.objectKey());
            storage.delete(uploaded.objectKey());
            storage.delete(uploaded.objectKey());
            assertThat(storage.exists(uploaded.objectKey())).isFalse();
            assertThat(storage.exists(second.objectKey())).isTrue();
        });
    }

    @Test
    void missingObjectsAreDistinctFromStorageFailures() {
        context().run(context -> {
            var storage = context.getBean(ObjectStorageService.class);
            String missing = ObjectKeys.submissionSourcePrefix(UUID.randomUUID()) + UUID.randomUUID() + ".java";
            assertThat(storage.exists(missing)).isFalse();
            assertCode(() -> storage.getMetadata(missing), OBJECT_NOT_FOUND);
            assertCode(() -> storage.download(missing), OBJECT_NOT_FOUND);
            storage.delete(missing);
        });
        context().withPropertyValues("storage.s3.bucket=missing-bucket").run(context -> {
            var storage = context.getBean(ObjectStorageService.class);
            String missing = ObjectKeys.submissionSourcePrefix(UUID.randomUUID()) + UUID.randomUUID() + ".java";
            assertCode(() -> storage.exists(missing), STORAGE_UNAVAILABLE);
        });
    }

    @Test
    void bucketIsPrivateAndApplicationCredentialsCannotWriteOutsideTheAllowedPrefix() {
        context().run(context -> {
            var storage = context.getBean(ObjectStorageService.class);
            ObjectMetadata object = storage.upload(source("Main.java", "class Main {}"));
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(endpoint() + "/" + BUCKET + "/" + object.objectKey())).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(403);
            var client = MinioClient.builder().endpoint(endpoint()).credentials(ACCESS, SECRET).region("us-east-1").build();
            assertThatThrownBy(() -> client.putObject(PutObjectArgs.builder().bucket(BUCKET).object("outside.java")
                    .stream(new ByteArrayInputStream(new byte[]{1}), 1, -1).build()))
                    .isInstanceOfSatisfying(ErrorResponseException.class, error -> assertThat(error.errorResponse().code()).isEqualTo("AccessDenied"));
        });
    }

    @Test
    void invalidUploadsAndTraversalAreRejectedBeforeObjectCreation() {
        context().run(context -> {
            var storage = context.getBean(ObjectStorageService.class);
            assertCode(() -> storage.upload(source("../Main.java", "source")), INVALID_UPLOAD);
            assertCode(() -> storage.upload(source("Main.exe", "source")), UNSUPPORTED_FILE_TYPE);
            assertCode(() -> storage.upload(source("Main.java", "")), INVALID_UPLOAD);
            assertCode(() -> storage.upload(source("Main.java", "binary\u0000data")), INVALID_UPLOAD);
            assertCode(() -> storage.upload(new SourceUpload(UUID.randomUUID(), "Main.java", 2,
                    new ByteArrayInputStream(new byte[]{(byte) 0xff, (byte) 0xfe}))), INVALID_UPLOAD);
            assertCode(() -> storage.download("../private"), INVALID_OBJECT_KEY);
            assertCode(() -> storage.delete("submissions/%2e%2e/source"), INVALID_OBJECT_KEY);
        });
    }

    @Test
    void actualBytesAndDeclaredSizeEnforceLimitsWithoutClosingTheCallerStream() {
        context().withPropertyValues("storage.s3.max-upload-size=8B").run(context -> {
            var storage = context.getBean(ObjectStorageService.class);
            var input = new ByteArrayInputStream("12345678".getBytes(StandardCharsets.UTF_8)) {
                @Override public void close() { fail("Upload must not close the caller-owned input stream"); }
            };
            assertThat(storage.upload(new SourceUpload(UUID.randomUUID(), "Main.java", 8, input)).size()).isEqualTo(8);
            assertCode(() -> storage.upload(source("Main.java", "123456789")), UPLOAD_TOO_LARGE);
            assertCode(() -> storage.upload(new SourceUpload(UUID.randomUUID(), "Main.java", 8,
                    new ByteArrayInputStream("123456789".getBytes(StandardCharsets.UTF_8)))), UPLOAD_TOO_LARGE);
            assertCode(() -> storage.upload(new SourceUpload(UUID.randomUUID(), "Main.java", 8,
                    new ByteArrayInputStream("123".getBytes(StandardCharsets.UTF_8)))), INVALID_UPLOAD);
        });
    }

    @Test
    void multipartSourceCanBeUploadedAndDownloadedWithBoundedBuffers() {
        context().run(context -> {
            var storage = context.getBean(ObjectStorageService.class);
            long length = 6L * 1024 * 1024;
            InputStream generated = new InputStream() {
                private long remaining = length;
                @Override public int read() { if (remaining == 0) return -1; remaining--; return 'a'; }
                @Override public int read(byte[] bytes, int offset, int count) {
                    if (remaining == 0) return -1;
                    int size = (int) Math.min(remaining, count);
                    Arrays.fill(bytes, offset, offset + size, (byte) 'a');
                    remaining -= size;
                    return size;
                }
            };
            ObjectMetadata object = storage.upload(new SourceUpload(UUID.randomUUID(), "Large.java", length, generated));
            assertThat(object.size()).isEqualTo(length);
            try (var download = storage.download(object.objectKey())) {
                byte[] buffer = new byte[8192];
                long total = 0;
                int read;
                while ((read = download.content().read(buffer)) != -1) {
                    for (int i = 0; i < read; i++) if (buffer[i] != 'a') fail("Downloaded bytes differ from source");
                    total += read;
                }
                assertThat(total).isEqualTo(length);
            }
        });
    }

    @Test
    void credentialAndTransportFailuresAreSanitized() {
        context().withPropertyValues("storage.s3.secret-key=" + UUID.randomUUID()).run(context -> {
            var storage = context.getBean(ObjectStorageService.class);
            assertCode(() -> storage.upload(source("Main.java", "class Main {}")), STORAGE_UNAVAILABLE);
        });
        context().withPropertyValues("storage.s3.endpoint=http://127.0.0.1:1", "storage.s3.request-timeout=1s").run(context -> {
            var storage = context.getBean(ObjectStorageService.class);
            assertCode(() -> storage.upload(source("Main.java", "class Main {}")), STORAGE_UNAVAILABLE);
        });
    }

    private static SourceUpload source(String filename, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return new SourceUpload(UUID.randomUUID(), filename, bytes.length, new ByteArrayInputStream(bytes));
    }
    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, StorageException.Code code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(StorageException.class, error -> {
            assertThat(error.getCode()).isEqualTo(code);
            assertThat(error.getMessage()).doesNotContain(ROOT_USER, ROOT_SECRET, ACCESS, SECRET);
            assertThat(error.getCause()).isNull();
        });
    }
}
