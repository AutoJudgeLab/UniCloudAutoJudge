package org.university.judge.worker.grading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class OutputSanitizerTest {

    @Test
    void shortOutputIsUnchanged() {
        assertEquals("42\n", OutputSanitizer.forStorage("42\n", 100));
        assertNull(OutputSanitizer.forStorage(null, 100));
    }

    @Test
    void removesNulBecausePostgresTextRejectsIt() {
        assertEquals("ab", OutputSanitizer.forStorage("a\u0000b", 100));
    }

    @Test
    void cutsOnCharacterBoundariesWithinTheByteLimit() {
        String cyrillic = "ж".repeat(10); // 2 bytes each
        assertEquals("ж".repeat(2), OutputSanitizer.forStorage(cyrillic, 5));
        String emoji = "😀".repeat(3); // 4 bytes each, surrogate pairs
        String cut = OutputSanitizer.forStorage(emoji, 7);
        assertEquals("😀", cut);
    }

    @Test
    void neverExceedsTheApiLimitEvenIfConfiguredHigher() {
        String stored = OutputSanitizer.forStorage("x".repeat(200_000), 1_000_000);
        assertTrue(stored.getBytes(StandardCharsets.UTF_8).length <= OutputSanitizer.API_LIMIT_BYTES);
    }
}
