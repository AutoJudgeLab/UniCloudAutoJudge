package org.university.judge.worker.grading;

import java.util.ArrayList;
import java.util.List;

/** Compares program output with the expected output, forgiving only line endings and trailing whitespace. */
public final class OutputComparator {

    private OutputComparator() { }

    public static boolean matches(String expected, String actual) {
        return normalize(expected).equals(normalize(actual));
    }

    static String normalize(String text) {
        if (text == null) return "";
        List<String> lines = new ArrayList<>(List.of(text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)));
        lines.replaceAll(String::stripTrailing);
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) lines.remove(lines.size() - 1);
        return String.join("\n", lines);
    }
}
