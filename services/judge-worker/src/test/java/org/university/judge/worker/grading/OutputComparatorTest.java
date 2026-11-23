package org.university.judge.worker.grading;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OutputComparatorTest {

    @Test
    void ignoresTrailingNewlinesAndTrailingSpaces() {
        assertTrue(OutputComparator.matches("42\n", "42"));
        assertTrue(OutputComparator.matches("a b  \nc\n\n\n", "a b\nc"));
        assertTrue(OutputComparator.matches("", "\n"));
    }

    @Test
    void ignoresWindowsLineEndings() {
        assertTrue(OutputComparator.matches("1\n2\n", "1\r\n2\r\n"));
    }

    @Test
    void keepsEverythingElseSignificant() {
        assertFalse(OutputComparator.matches("42", "43"));
        assertFalse(OutputComparator.matches("a b", "a  b"));
        assertFalse(OutputComparator.matches("1\n2", "1\n\n2"));
        assertFalse(OutputComparator.matches("abc", "ABC"));
        assertFalse(OutputComparator.matches("x", " x"));
    }
}
