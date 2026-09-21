package com.example.dirtyingredients;

import android.net.Uri;

public class WalmartUrlBuilder {

    /**
     * Cleans USDA titles into natural retail search terms for Walmart.
     */
    public static String buildSearchUrl(String usdaTerm) {
        if (usdaTerm == null || usdaTerm.trim().isEmpty()) {
            return "https://www.walmart.com";
        }

        String cleaned = usdaTerm;

        // 1. Remove brand/manufacturer parentheticals e.g. "(Post Consumer Brands, LLC)"
        if (cleaned.contains("(")) {
            cleaned = cleaned.substring(0, cleaned.indexOf("("));
        }

        // 2. Take primary description prior to secondary comma details if present
        // e.g. "OREO CEREAL, OREO" -> "OREO CEREAL"
        if (cleaned.contains(",")) {
            String[] parts = cleaned.split(",");
            if (parts.length > 0) {
                cleaned = parts[0];
            }
        }

        // 3. Remove excess symbols and whitespace
        cleaned = cleaned.replaceAll("[^a-zA-Z0-9\\s]", " ")
                         .replaceAll("\\s+", " ")
                         .trim();

        // Fallback if cleaning stripped everything
        if (cleaned.isEmpty()) {
            cleaned = usdaTerm.trim();
        }

        return new Uri.Builder()
                .scheme("https")
                .authority("www.walmart.com")
                .path("search")
                .appendQueryParameter("q", cleaned)
                .build()
                .toString();
    }
}
