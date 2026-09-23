package com.example.barelabel;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public class FlaggedIngredientManager {
    private static final String TAG = "FlaggedManager";

    // Memory cache for superior ingredients read from assets
    private static List<String> cachedSuperiorIngredients = null;

    public static class MatchResult {
        public Map<String, List<String>> categoryMap = new LinkedHashMap<>();

        public boolean hasMatches() {
            return categoryMap != null && !categoryMap.isEmpty();
        }
    }

    /**
     * Loads and caches superior ingredients line-by-line from src/main/assets/superior_ingredients.txt
     */
    private static synchronized List<String> getSuperiorIngredientsList(Context context) {
        if (cachedSuperiorIngredients != null) {
            return cachedSuperiorIngredients;
        }

        List<String> list = new ArrayList<>();
        if (context == null || context.getAssets() == null) {
            return list;
        }

        // Check for both spelling variants in case of typos in file naming
        String fileName = "superior_ingredients.txt";
        try {
            String[] assets = context.getAssets().list("");
            if (assets != null) {
                for (String asset : assets) {
                    if ("superior_ingrrdients.txt".equalsIgnoreCase(asset)) {
                        fileName = asset;
                        break;
                    }
                }
            }
        } catch (Exception ignored) {}

        try (InputStream is = context.getAssets().open(fileName);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    list.add(trimmed.toLowerCase(Locale.US));
                }
            }
            cachedSuperiorIngredients = list;
        } catch (Exception e) {
            Log.e(TAG, "Failed to load " + fileName + " from assets", e);
        }

        return list;
    }

    /**
     * Checks if a target ingredient token matches any superior ingredient listed in the asset file.
     * Uses regex word boundaries to avoid false positives.
     */
    public static boolean isSuperiorIngredient(Context context, String ingredient) {
        if (ingredient == null || ingredient.trim().isEmpty()) {
            return false;
        }

        List<String> superiorList = getSuperiorIngredientsList(context);
        if (superiorList.isEmpty()) {
            return false;
        }

        String lowerCandidate = ingredient.toLowerCase(Locale.US).trim();

        for (String superiorTerm : superiorList) {
            // Check exact phrase match or word-boundary match
            String patternString = "\\b" + Pattern.quote(superiorTerm) + "\\b";
            Pattern pattern = Pattern.compile(patternString);
            if (pattern.matcher(lowerCandidate).find()) {
                return true;
            }
        }

        return false;
    }

    /**
     * Builds a word-boundary regex for a flagged term that tolerates irregular
     * whitespace between words and a plural "s" on the final word, so
     * "carob bean gums" matches the listed term "carob bean gum".
     */
    private static String phrasePattern(String term) {
        String[] words = term.trim().split("\\s+");
        StringBuilder sb = new StringBuilder("\\b");
        for (int i = 0; i < words.length; i++) {
            if (i > 0) sb.append("\\s+");
            sb.append(Pattern.quote(words[i]));
            if (i == words.length - 1) sb.append("s?");
        }
        sb.append("\\b");
        return sb.toString();
    }

    public static MatchResult analyzeIngredients(Context context, String rawIngredientsText) {
        MatchResult result = new MatchResult();

        // Safe null/empty check
        if (rawIngredientsText == null || rawIngredientsText.trim().isEmpty()) {
            return result;
        }

        String lowerCaseIngredients = rawIngredientsText.toLowerCase(Locale.US);

        try {
            // Safe context and assets check
            if (context == null || context.getAssets() == null) {
                return result;
            }

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

            // Safely read asset JSON file using BufferedReader
            StringBuilder jsonBuilder = new StringBuilder();
            try (InputStream is = context.getAssets().open("flagged_ingredients.json");
                 BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    jsonBuilder.append(line);
                }
            } catch (Exception e) {
                Log.e(TAG, "flagged_ingredients.json not found in assets folder!", e);
                return result; // Safe fallback without crashing
            }

            String jsonString = jsonBuilder.toString().trim();
            if (jsonString.isEmpty()) {
                return result;
            }

            JSONObject rootJson = new JSONObject(jsonString);
            Iterator<String> categoryKeys = rootJson.keys();

            while (categoryKeys.hasNext()) {
                String categoryName = categoryKeys.next();
                JSONObject categoryObj = rootJson.optJSONObject(categoryName);
                if (categoryObj == null) continue;

                // Consult SharedPreferences (sticky toggle states)
                boolean defaultEnabled = categoryObj.optBoolean("default_enabled", true);
                boolean isCategoryEnabled = prefs.getBoolean(categoryName, defaultEnabled);

                if (isCategoryEnabled) {
                    JSONArray ingredientsArray = categoryObj.optJSONArray("ingredients");
                    if (ingredientsArray == null) continue;

                    List<String> matchedInThisCategory = new ArrayList<>();

                    for (int i = 0; i < ingredientsArray.length(); i++) {
                        String rawIngredient = ingredientsArray.optString(i, "").trim();
                        if (rawIngredient.isEmpty()) continue;

                        String lowerIngredient = rawIngredient.toLowerCase(Locale.US);

                        // Word-boundary phrase matching, tolerant of irregular
                        // whitespace and a plural "s" on the final word, so label
                        // phrasing like "carob bean gums" still matches the listed
                        // term "carob bean gum".
                        Pattern pattern = Pattern.compile(phrasePattern(lowerIngredient));

                        if (pattern.matcher(lowerCaseIngredients).find()) {
                            if (!matchedInThisCategory.contains(rawIngredient)) {
                                matchedInThisCategory.add(rawIngredient);
                            }
                        }
                    }

                    if (!matchedInThisCategory.isEmpty()) {
                        result.categoryMap.put(categoryName, matchedInThisCategory);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing flagged ingredients", e);
        }

        return result;
    }
}
