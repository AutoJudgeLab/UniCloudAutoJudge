package org.university.judge.worker.grading;

import java.nio.charset.StandardCharsets;

/** Makes program output storable: PostgreSQL text rejects NUL and the API caps each output at 64 KiB of UTF-8. */
public final class OutputSanitizer {

    /** Hard limit enforced by EvaluationCompletionService. */
    public static final int API_LIMIT_BYTES = 65536;

    private OutputSanitizer() { }

    public static String forStorage(String output, int maxBytes) {
        if (output == null) return null;
        int limit = Math.max(0, Math.min(maxBytes, API_LIMIT_BYTES));
        String clean = output.indexOf('\u0000') < 0 ? output : output.replace("\u0000", "");
        if (clean.getBytes(StandardCharsets.UTF_8).length <= limit) return clean;
        StringBuilder kept = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < clean.length(); ) {
            int codePoint = clean.codePointAt(i);
            int size = codePoint < 0x80 ? 1 : codePoint < 0x800 ? 2 : codePoint < 0x10000 ? 3 : 4;
            if (bytes + size > limit) break;
            kept.appendCodePoint(codePoint);
            bytes += size;
            i += Character.charCount(codePoint);
        }
        return kept.toString();
    }
}
