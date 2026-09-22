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

        String lowerCaseIngredients = rawIngredientsText.toLowerCase();

        try {
            // Check if context or assets are available
            if (context == null || context.getAssets() == null) {
                return result;
            }

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

            // Attempt to load asset file safely
            InputStream is = null;
            try {
                is = context.getAssets().open("flagged_ingredients.json");
            } catch (Exception e) {
                Log.e(TAG, "flagged_ingredients.json not found in assets folder!", e);
                return result; // Returns clean result without crashing thread
            }

            byte[] buffer = new byte[is.available()];
            is.read(buffer);
            is.close();

            String jsonString = new String(buffer, StandardCharsets.UTF_8);
            if (jsonString.trim().isEmpty()) {
                return result;
            }

            JSONObject rootJson = new JSONObject(jsonString);
            Iterator<String> categoryKeys = rootJson.keys();

            while (categoryKeys.hasNext()) {
                String categoryName = categoryKeys.next();
                JSONObject categoryObj = rootJson.optJSONObject(categoryName);
                if (categoryObj == null) continue;

                boolean defaultEnabled = categoryObj.optBoolean("default_enabled", true);
                boolean isCategoryEnabled = prefs.getBoolean(categoryName, defaultEnabled);

                if (isCategoryEnabled) {
                    JSONArray ingredientsArray = categoryObj.optJSONArray("ingredients");
                    if (ingredientsArray == null) continue;

                    List<String> matchedInThisCategory = new ArrayList<>();

                    for (int i = 0; i < ingredientsArray.length(); i++) {
                        String ingredient = ingredientsArray.optString(i, "").trim().toLowerCase();

                        if (!ingredient.isEmpty() && lowerCaseIngredients.contains(ingredient)) {
                            if (!matchedInThisCategory.contains(ingredient)) {
                                matchedInThisCategory.add(ingredient);
                            }
                        }
                    }

                    if (!matchedInThisCategory.isEmpty()) {
                        result.categoryMap.put(categoryName, matchedInThisCategory);
                    }
                }
            }
        } catch (Exception e) {
            // Log error safely so thread completes execution
            Log.e(TAG, "Error processing flagged ingredients", e);
        }

        return result;
    }
}
