package org.university.judge.storage.minio;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.util.unit.DataSize;
import org.university.judge.storage.ObjectKeys;
import org.university.judge.storage.SourceUpload;
import org.university.judge.storage.StorageException;
import org.university.judge.storage.config.S3StorageProperties;

import static org.assertj.core.api.Assertions.*;
import static org.university.judge.storage.StorageException.Code.*;

class SourceValidationTest {
    @Test
    void stagesUtf8PreservesBytesAndErasesTemporaryFile() throws Exception {
        byte[] content = "// Unicode: λ\r\nclass Main {}\n".getBytes(StandardCharsets.UTF_8);
        var staged = ValidatedSource.stage(upload("Mäin.JAVA", content, content.length), properties(1024));
        assertThat(staged.extension()).isEqualTo("java");
        assertThat(Files.readAllBytes(staged.path())).isEqualTo(content);
        staged.close();
        assertThat(Files.exists(staged.path())).isFalse();
    }

    @Test
    void rejectsTraversalControlCharactersAndUnsupportedExtensions() {
        byte[] content = "class Main {}".getBytes(StandardCharsets.UTF_8);
        for (String name : new String[]{"../Main.java", "a/Main.java", "a\\Main.java", "C:Main.java", ".java", "Main\r\n.java", " Main.java", "a..java"}) {
            rejects(() -> ValidatedSource.stage(upload(name, content, content.length), properties(1024)), INVALID_UPLOAD);
        }
        for (String name : new String[]{"Main.exe", "Main.java.zip", "Main", "Main.html"}) {
            rejects(() -> ValidatedSource.stage(upload(name, content, content.length), properties(1024)), UNSUPPORTED_FILE_TYPE);
        }
    }

    @Test
    void rejectsEmptyWhitespaceBinaryAndMalformedUtf8Content() {
        for (byte[] content : new byte[][]{new byte[0], " \r\n\t".getBytes(StandardCharsets.UTF_8), {0, 1, 2}, {(byte) 0xc3, 0x28}, {'P', 'K', 3, 4, 0}}) {
            rejects(() -> ValidatedSource.stage(upload("Main.java", content, content.length), properties(1024)), INVALID_UPLOAD);
        }
    }

    @Test
    void enforcesActualAndDeclaredLengthsAtTheBoundary() {
        byte[] content = "12345678".getBytes(StandardCharsets.UTF_8);
        try (var staged = ValidatedSource.stage(upload("Main.java", content, 8), properties(8))) {
            assertThat(staged.size()).isEqualTo(8);
        }
        rejects(() -> ValidatedSource.stage(upload("Main.java", content, 9), properties(8)), UPLOAD_TOO_LARGE);
        rejects(() -> ValidatedSource.stage(upload("Main.java", content, 7), properties(8)), INVALID_UPLOAD);
        rejects(() -> ValidatedSource.stage(upload("Main.java", content, 9), properties(16)), INVALID_UPLOAD);
        byte[] tooLarge = "123456789".getBytes(StandardCharsets.UTF_8);
        rejects(() -> ValidatedSource.stage(upload("Main.java", tooLarge, 8), properties(8)), UPLOAD_TOO_LARGE);
    }

    @Test
    void objectKeysRejectArbitraryPathsAndReserveEvaluationNamespace() {
        for (String key : new String[]{"../source", "/absolute", "submissions/../Main.java", "https://host/object", "submissions/%2e%2e/source"}) {
            rejects(() -> ObjectKeys.validate(key), INVALID_OBJECT_KEY);
        }
        String source = ObjectKeys.submissionSourcePrefix(UUID.randomUUID()) + UUID.randomUUID() + ".java";
        ObjectKeys.validate(source);
        String artifact = ObjectKeys.evaluationArtifactPrefix(UUID.randomUUID(), UUID.randomUUID()) + UUID.randomUUID() + ".txt";
        ObjectKeys.validate(artifact);
    }

    private static SourceUpload upload(String name, byte[] content, long declared) {
        return new SourceUpload(UUID.randomUUID(), name, declared, new ByteArrayInputStream(content));
    }
    private static S3StorageProperties properties(long maximum) {
        return new S3StorageProperties(URI.create("http://localhost:9000"), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "test-bucket", "us-east-1", DataSize.ofBytes(maximum), Set.of("java", "py"), Duration.ofSeconds(5));
    }
    private static void rejects(ThrowingCallable action, StorageException.Code code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(StorageException.class, error -> assertThat(error.getCode()).isEqualTo(code));
    }
}
