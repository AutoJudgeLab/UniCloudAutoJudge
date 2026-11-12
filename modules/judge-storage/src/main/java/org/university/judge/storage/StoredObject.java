package org.university.judge.storage;

import java.io.IOException;
import java.io.InputStream;

/** Streaming download. Always close this object, including when abandoning a read. */
public record StoredObject(ObjectMetadata metadata, InputStream content) implements AutoCloseable {
    @Override
    public void close() throws IOException { content.close(); }
}
