package org.university.judge.storage;

/** ETag is an opaque storage identifier, not necessarily a content hash. */
public record ObjectMetadata(String objectKey, String originalFilename, long size, String contentType, String etag) { }
