package org.university.judge.storage.minio;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.university.judge.storage.SourceUpload;
import org.university.judge.storage.PreparedSource;
import org.university.judge.storage.StorageException;
import org.university.judge.storage.config.S3StorageProperties;

import static org.university.judge.storage.StorageException.Code.*;

/** Bounded disk staging validates the entire source before any object is uploaded. */
record ValidatedSource(Path path, String originalFilename, String extension, long size, String sha256) implements PreparedSource {
    static ValidatedSource stage(SourceUpload upload, S3StorageProperties properties) {
        if (upload == null || upload.content() == null || upload.submissionId() == null || upload.contentLength() <= 0) {
            throw new StorageException(INVALID_UPLOAD);
        }
        long maximum = properties.maxUploadSize().toBytes();
        if (upload.contentLength() > maximum) throw new StorageException(UPLOAD_TOO_LARGE);
        String name = upload.originalFilename();
        if (name == null || name.isBlank() || !name.equals(name.strip()) || name.length() > 255
                || name.getBytes(StandardCharsets.UTF_8).length > 512 || name.startsWith(".")
                || name.contains("..") || name.contains("/") || name.contains("\\") || name.contains(":")
                || name.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT)) {
            throw new StorageException(INVALID_UPLOAD);
        }
        int dot = name.lastIndexOf('.');
        String extension = dot < 1 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!properties.allowedExtensions().contains(extension)) throw new StorageException(UNSUPPORTED_FILE_TYPE);
        Path path = null;
        try {
            // Platform-generated filename and restrictive default temp-file permissions.
            path = Files.createTempFile("judge-source-", ".upload");
            long size = 0;
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            try (var output = Files.newOutputStream(path)) {
                int read;
                while ((read = upload.content().read(buffer, 0, (int) Math.min(buffer.length, maximum - size + 1))) != -1) {
                    if (read == 0) continue;
                    size += read;
                    if (size > maximum) throw new StorageException(UPLOAD_TOO_LARGE);
                    if (size > upload.contentLength()) throw new StorageException(INVALID_UPLOAD);
                    output.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                }
            }
            if (size != upload.contentLength()) throw new StorageException(INVALID_UPLOAD);
            validateText(path);
            ValidatedSource result = new ValidatedSource(path, name, extension, size, java.util.HexFormat.of().formatHex(digest.digest()));
            path = null;
            return result;
        } catch (java.security.NoSuchAlgorithmException ignored) {
            throw new StorageException(STORAGE_UNAVAILABLE);
        } catch (IOException ignored) {
            throw new StorageException(INVALID_UPLOAD);
        } finally {
            if (path != null) erase(path);
        }
    }

    private static void validateText(Path path) throws IOException {
        var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (var reader = new InputStreamReader(Files.newInputStream(path), decoder)) {
            char[] buffer = new char[8192];
            boolean nonWhitespace = false;
            int read;
            while ((read = reader.read(buffer)) != -1) {
                for (int i = 0; i < read; i++) {
                    char c = buffer[i];
                    if (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t' && c != '\f') {
                        throw new StorageException(INVALID_UPLOAD);
                    }
                    if (!Character.isWhitespace(c) && c != '\uFEFF') nonWhitespace = true;
                }
            }
            if (!nonWhitespace) throw new StorageException(INVALID_UPLOAD);
        }
    }

    private static void erase(Path path) {
        try { Files.deleteIfExists(path); }
        catch (IOException ignored) { throw new StorageException(STORAGE_UNAVAILABLE); }
    }

    @Override
    public void close() { erase(path); }
}
