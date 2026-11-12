package org.university.judge.storage;

import java.io.InputStream;
import java.util.UUID;

/** Caller retains ownership of content and must close it. MIME types are not accepted. */
public record SourceUpload(UUID submissionId, String originalFilename, long contentLength, InputStream content) {
    @Override
    public String toString() { return "SourceUpload[content=REDACTED]"; }
}
