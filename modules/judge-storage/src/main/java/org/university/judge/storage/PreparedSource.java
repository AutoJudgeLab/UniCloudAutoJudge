package org.university.judge.storage;

/** Validated, disk-staged source. Close after upload/replay to erase temporary data. */
public interface PreparedSource extends AutoCloseable {
    String originalFilename();
    String extension();
    long size();
    String sha256();
    @Override void close();
}
