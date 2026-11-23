package org.university.judge.storage;

/** Private object storage. Callers enforce resource ownership before using keys. */
public interface ObjectStorageService {
    PreparedSource prepare(String originalFilename, long length, java.io.InputStream content);
    String newSourceKey(java.util.UUID submissionId, PreparedSource source);
    /** Trusted application code must register the fresh planned key before calling this method. */
    ObjectMetadata upload(String plannedKey, PreparedSource source);
    ObjectMetadata upload(SourceUpload upload);
    StoredObject download(String objectKey);
    /** Idempotent for a missing object; bucket/transport/permission failures still fail. */
    void delete(String objectKey);
    boolean exists(String objectKey);
    ObjectMetadata getMetadata(String objectKey);
}
