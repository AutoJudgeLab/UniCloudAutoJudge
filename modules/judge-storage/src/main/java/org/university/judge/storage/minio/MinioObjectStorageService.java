package org.university.judge.storage.minio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import okhttp3.OkHttpClient;
import org.university.judge.storage.ObjectKeys;
import org.university.judge.storage.ObjectMetadata;
import org.university.judge.storage.ObjectStorageService;
import org.university.judge.storage.SourceUpload;
import org.university.judge.storage.StorageException;
import org.university.judge.storage.StoredObject;
import org.university.judge.storage.PreparedSource;
import org.university.judge.storage.config.S3StorageProperties;

import static org.university.judge.storage.StorageException.Code.*;

public final class MinioObjectStorageService implements ObjectStorageService, AutoCloseable {
    private static final String CONTENT_TYPE = "text/plain; charset=utf-8";
    private static final String FILENAME_METADATA = "original-filename-b64";
    private final MinioClient client;
    private final OkHttpClient http;
    private final S3StorageProperties properties;

    public MinioObjectStorageService(MinioClient client, OkHttpClient http, S3StorageProperties properties) {
        this.client = client;
        this.http = http;
        this.properties = properties;
    }

    @Override
    public ObjectMetadata upload(SourceUpload upload) {
        try (ValidatedSource source = ValidatedSource.stage(upload, properties)) {
            return upload(newSourceKey(upload.submissionId(), source), source);
        }
    }

    @Override
    public PreparedSource prepare(String filename, long length, java.io.InputStream content) {
        return ValidatedSource.stage(new SourceUpload(UUID.randomUUID(), filename, length, content), properties);
    }

    @Override
    public String newSourceKey(UUID submissionId, PreparedSource source) {
        if (!(source instanceof ValidatedSource)) throw new StorageException(INVALID_UPLOAD);
        return ObjectKeys.submissionSourcePrefix(submissionId) + UUID.randomUUID() + "." + source.extension();
    }

    @Override
    public ObjectMetadata upload(String key, PreparedSource prepared) {
        ObjectKeys.validate(key);
        if (!(prepared instanceof ValidatedSource source) || !key.contains("/source/") || !key.endsWith("." + source.extension())) {
            throw new StorageException(INVALID_UPLOAD);
        }
        try (var input = Files.newInputStream(source.path())) {
            // Keys are freshly generated per attempt, never reused by submission retries.
            String filename = Base64.getUrlEncoder().withoutPadding().encodeToString(source.originalFilename().getBytes(StandardCharsets.UTF_8));
            var response = client.putObject(PutObjectArgs.builder().bucket(properties.bucket()).object(key)
                    .stream(input, source.size(), 5L * 1024 * 1024).contentType(CONTENT_TYPE)
                    .userMetadata(Map.of(FILENAME_METADATA, filename)).build());
            return new ObjectMetadata(key, source.originalFilename(), source.size(), CONTENT_TYPE, response.etag());
        } catch (StorageException exception) {
            throw exception;
        } catch (Exception exception) {
            throw failure(exception);
        }
    }

    @Override
    public StoredObject download(String objectKey) {
        ObjectMetadata metadata = getMetadata(objectKey);
        try {
            return new StoredObject(metadata, client.getObject(GetObjectArgs.builder().bucket(properties.bucket()).object(objectKey).build()));
        } catch (Exception exception) { throw failure(exception); }
    }

    @Override
    public void delete(String objectKey) {
        ObjectKeys.validate(objectKey);
        try { client.removeObject(RemoveObjectArgs.builder().bucket(properties.bucket()).object(objectKey).build()); }
        catch (Exception exception) {
            StorageException failure = failure(exception);
            if (failure.getCode() != OBJECT_NOT_FOUND) throw failure;
        }
    }

    @Override
    public boolean exists(String objectKey) {
        try { getMetadata(objectKey); return true; }
        catch (StorageException exception) {
            if (exception.getCode() == OBJECT_NOT_FOUND) return false;
            throw exception;
        }
    }

    @Override
    public ObjectMetadata getMetadata(String objectKey) {
        ObjectKeys.validate(objectKey);
        try {
            var response = client.statObject(StatObjectArgs.builder().bucket(properties.bucket()).object(objectKey).build());
            String encoded = response.userMetadata().get(FILENAME_METADATA);
            if (encoded == null || encoded.length() > 1024) throw new StorageException(STORAGE_UNAVAILABLE);
            String name = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            return new ObjectMetadata(objectKey, name, response.size(), response.contentType(), response.etag());
        } catch (StorageException exception) { throw exception; }
        catch (Exception exception) { throw failure(exception); }
    }

    private StorageException failure(Exception exception) {
        if (exception instanceof ErrorResponseException response
                && ("NoSuchKey".equals(response.errorResponse().code()) || "NoSuchObject".equals(response.errorResponse().code()))) {
            return new StorageException(OBJECT_NOT_FOUND);
        }
        if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
        return new StorageException(STORAGE_UNAVAILABLE);
    }

    @Override
    public void close() throws IOException {
        http.dispatcher().executorService().shutdown();
        http.connectionPool().evictAll();
        if (http.cache() != null) http.cache().close();
    }
}
