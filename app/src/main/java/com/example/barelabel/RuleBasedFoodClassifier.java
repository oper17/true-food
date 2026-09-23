package com.example.barelabel;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class RuleBasedFoodClassifier {

    // Regex patterns for text cleaning
    private static final Pattern PARENTHESES_PATTERN = Pattern.compile("\\(.*\\)");
    private static final Pattern CLEAN_PUNCTUATION = Pattern.compile("[^a-zA-Z0-9\\s,]");

    // Known brand list to strip out if appearing before commas
    private static final List<String> BRANDS = Arrays.asList(
        "dannon", "yoplait", "kraft", "kellogg", "quaker", "nestle", "general mills"
    );

    // Primary Keyword-to-Category Dictionary
    private static final Map<String, String> KEYWORD_CATEGORY_MAP = new HashMap<>();

    static {
        // Map common root nouns to standardized food types
        KEYWORD_CATEGORY_MAP.put("yogurt", "Yogurt");
        KEYWORD_CATEGORY_MAP.put("cheese", "Cheese");
        KEYWORD_CATEGORY_MAP.put("bread", "Bread");
        KEYWORD_CATEGORY_MAP.put("cereal", "Cereal");
        KEYWORD_CATEGORY_MAP.put("milk", "Milk");
        KEYWORD_CATEGORY_MAP.put("cracker", "Crackers");
        KEYWORD_CATEGORY_MAP.put("crackers", "Crackers");
        KEYWORD_CATEGORY_MAP.put("chip", "Chips & Crisps");
        KEYWORD_CATEGORY_MAP.put("chips", "Chips & Crisps");
        KEYWORD_CATEGORY_MAP.put("pretzel", "Pretzels");
        KEYWORD_CATEGORY_MAP.put("pretzels", "Pretzels");
        KEYWORD_CATEGORY_MAP.put("pasta", "Pasta");
        KEYWORD_CATEGORY_MAP.put("butter", "Butter & Spreads");
    }

    /**
     * Attempts to classify a USDA food description using rule-based heuristics.
     * @param rawDescription The official USDA food description
     * @return Extracted category name, or NULL if no high-confidence rule matched (triggers ML fallback).
     */
    public String classify(String rawDescription) {
        if (rawDescription == null || rawDescription.trim().isEmpty()) {
            return null;
        }

        // 1. Text Normalization
        String cleaned = PARENTHESES_PATTERN.matcher(rawDescription).replaceAll(""); // Remove (notes)
        cleaned = CLEAN_PUNCTUATION.matcher(cleaned).replaceAll("").toLowerCase().trim();

        // 2. Split by comma (USDA puts the main item class before the 1st comma)
        String[] parts = cleaned.split(",");
        String primarySegment = parts[0].trim();

        // Strip leading brand name if present (e.g., "KRAFT, Cheddar Cheese" -> "Cheddar Cheese")
        for (String brand : BRANDS) {
            if (primarySegment.startsWith(brand + " ")) {
                primarySegment = primarySegment.substring(brand.length()).trim();
            }
        }

        // 3. Match Head Noun against Taxonomy Map
        // Tokenize primary segment into individual words
        String[] tokens = primarySegment.split("\\s+");

        for (String token : tokens) {
            if (KEYWORD_CATEGORY_MAP.containsKey(token)) {
                return KEYWORD_CATEGORY_MAP.get(token);
            }
        }

        // 4. Secondary Pass: Scan secondary segments if USDA used inverted order
        // e.g. "Whole Milk, Greek Yogurt"
        for (int i = 1; i < parts.length; i++) {
            String[] segmentTokens = parts[i].trim().split("\\s+");
            for (String token : segmentTokens) {
                if (KEYWORD_CATEGORY_MAP.containsKey(token)) {
                    return KEYWORD_CATEGORY_MAP.get(token);
                }
            }
        }

        // Return null to signal that the ML Classifier layer should take over
        return null;
    }
}
