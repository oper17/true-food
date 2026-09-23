package com.example.dirtyingredients.util;

import java.util.Locale;

/** Shared text normalization for search keys, dedupe keys, and term lists. */
public final class StringNormalizer {
    private StringNormalizer() {
    }

    public static String normalize(String s) {
        return s.toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9% -]", " ")
                .replaceAll("\\s+", " ").trim();
    }
}
