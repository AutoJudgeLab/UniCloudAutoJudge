package org.university.judge.api.auth;

import java.util.Locale;

public final class Identifiers {
    private Identifiers() {
    }

    public static String normalize(String value) {
        return value == null ? null : value.strip().toLowerCase(Locale.ROOT);
    }
}
