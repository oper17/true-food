package com.example.dirtyingredients;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.regex.Pattern;
import java.io.BufferedReader;


public class FlaggedIngredientManager {
    private static final String TAG = "FlaggedManager";

    public static class MatchResult {
        public Map<String, List<String>> categoryMap = new LinkedHashMap<>();

        public boolean hasMatches() {
            return categoryMap != null && !categoryMap.isEmpty();
        }
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

                    // Word-boundary matching to avoid partial substring false positives
                    String patternString = "\\b" + Pattern.quote(lowerIngredient) + "\\b";
                    Pattern pattern = Pattern.compile(patternString);

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
