package com.example.barelabel.util;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Shared text normalization for search keys, dedupe keys, and term lists. */
public final class StringNormalizer {
    private StringNormalizer() {
    }

    public static String normalize(String s) {
        return s.toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9% -]", " ")
                .replaceAll("\\s+", " ").trim();
    }

    /** Lowercase alphanumeric tokens of a text, for token-overlap matching. */
    public static Set<String> wordTokens(String text) {
        Set<String> tokens = new HashSet<>();
        if (text == null || text.trim().isEmpty()) return tokens;
        for (String t : text.toLowerCase(Locale.US).split("[^a-z0-9]+")) {
            if (!t.isEmpty()) tokens.add(t);
        }
        return tokens;
    }
}
