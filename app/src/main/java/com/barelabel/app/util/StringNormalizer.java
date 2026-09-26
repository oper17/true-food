package com.barelabel.app.util;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Shared text normalization for search keys, dedupe keys, and term lists. */
public final class StringNormalizer {
    private StringNormalizer() {
    }

    // Precompiled: normalize()/wordTokens() run on hot paths and
    // String.replaceAll/split recompile their regex on every call.
    private static final java.util.regex.Pattern NON_ALNUM =
            java.util.regex.Pattern.compile("[^a-z0-9% -]");
    private static final java.util.regex.Pattern WHITESPACE =
            java.util.regex.Pattern.compile("\\s+");
    private static final java.util.regex.Pattern TOKEN_SPLIT =
            java.util.regex.Pattern.compile("[^a-z0-9]+");

    public static String normalize(String s) {
        return WHITESPACE.matcher(
                NON_ALNUM.matcher(s.toLowerCase(Locale.US)).replaceAll(" ")
        ).replaceAll(" ").trim();
    }

    /** Lowercase alphanumeric tokens of a text, for token-overlap matching. */
    public static Set<String> wordTokens(String text) {
        Set<String> tokens = new HashSet<>();
        if (text == null || text.trim().isEmpty()) return tokens;
        for (String t : TOKEN_SPLIT.split(text.toLowerCase(Locale.US))) {
            if (!t.isEmpty()) tokens.add(t);
        }
        return tokens;
    }

    /**
     * Natural English casing for display: first letter of each word capitalized,
     * rest lowercased. Apostrophes don't trigger capitalization ("Pic's", not
     * "Pic'S"); hyphens and other separators do ("Sugar-Free").
     */
    public static String toTitleCase(String text) {
        if (text == null) return "";
        String lower = text.toLowerCase(Locale.US);
        StringBuilder out = new StringBuilder(lower.length());
        boolean capNext = true;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                out.append(capNext ? Character.toUpperCase(c) : c);
                capNext = false;
            } else {
                out.append(c);
                capNext = c != '\'';
            }
        }
        return out.toString();
    }
}
