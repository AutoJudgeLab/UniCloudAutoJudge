package org.university.judge.storage;

import java.util.UUID;
import java.util.regex.Pattern;

public final class ObjectKeys {
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Pattern KEY = Pattern.compile("submissions/" + UUID_PATTERN
            + "/(?:source|evaluations/" + UUID_PATTERN + "/artifacts)/" + UUID_PATTERN + "\\.[a-z0-9]{1,10}");

    private ObjectKeys() { }

    public static String submissionSourcePrefix(UUID submissionId) {
        if (submissionId == null) throw new StorageException(StorageException.Code.INVALID_OBJECT_KEY);
        return "submissions/" + submissionId + "/source/";
    }

    /** Reserved namespace for a future evaluation artifact writer; no evaluation is performed. */
    public static String evaluationArtifactPrefix(UUID submissionId, UUID evaluationId) {
        if (submissionId == null || evaluationId == null) throw new StorageException(StorageException.Code.INVALID_OBJECT_KEY);
        return "submissions/" + submissionId + "/evaluations/" + evaluationId + "/artifacts/";
    }

    public static void validate(String key) {
        if (key == null || !KEY.matcher(key).matches()) throw new StorageException(StorageException.Code.INVALID_OBJECT_KEY);
    }
}
