package org.university.judge.storage;

/** Sanitized failures: SDK messages, signed requests and credentials are never propagated. */
public class StorageException extends RuntimeException {
    public enum Code {
        INVALID_UPLOAD, UNSUPPORTED_FILE_TYPE, UPLOAD_TOO_LARGE, INVALID_OBJECT_KEY, OBJECT_NOT_FOUND, STORAGE_UNAVAILABLE
    }
    private final Code code;
    public StorageException(Code code) {
        super(switch (code) {
            case INVALID_UPLOAD -> "Upload must contain nonempty UTF-8 source text with a valid filename and exact length";
            case UNSUPPORTED_FILE_TYPE -> "Source file extension is not allowed";
            case UPLOAD_TOO_LARGE -> "Upload exceeds the configured size limit";
            case INVALID_OBJECT_KEY -> "Object key is invalid";
            case OBJECT_NOT_FOUND -> "Object not found";
            case STORAGE_UNAVAILABLE -> "Object storage operation failed";
        });
        this.code = code;
    }
    public Code getCode() { return code; }
}
