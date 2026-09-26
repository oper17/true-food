package com.barelabel.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class FlaggedIngredientManager {
    private static final String TAG = "FlaggedManager";

    /**
     * One flagged term, compiled once. The JSON asset is parsed and every
     * term's regex is compiled exactly once per process — the old code
     * re-read the asset and recompiled all ~200 patterns on every call,
     * and analyzeIngredients() runs per candidate (hundreds of times per
     * search).
     */
    private static final class CompiledTerm {
        final String category;
        final String displayTerm;
        final Pattern pattern;

        CompiledTerm(String category, String displayTerm, Pattern pattern) {
            this.category = category;
            this.displayTerm = displayTerm;
            this.pattern = pattern;
        }
    }

    private static volatile List<CompiledTerm> compiledFlaggedTerms = null;
    private static volatile Map<String, Boolean> flaggedCategoryDefaults = null;
    private static volatile boolean flaggedLoadFailed = false;
    private static final Object FLAG_LOCK = new Object();

    // Memory cache for superior ingredients read from assets
    private static List<String> cachedSuperiorIngredients = null;
    private static volatile List<Pattern> compiledSuperiorPatterns = null;
    private static final Object SUPERIOR_LOCK = new Object();

    public static class MatchResult {
        public Map<String, List<String>> categoryMap = new LinkedHashMap<>();

        public boolean hasMatches() {
            return categoryMap != null && !categoryMap.isEmpty();
        }
    }

    /**
     * Backing store for the legacy default SharedPreferences, without the
     * deprecated android.preference.PreferenceManager. The file name matches
     * getDefaultSharedPreferences() exactly, so existing toggles survive.
     */
    static SharedPreferences defaultPrefs(Context context) {
        return context.getSharedPreferences(
                context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
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

    /** Superior-ingredient regexes, compiled once. Terms are pre-lowercased on load. */
    private static List<Pattern> getSuperiorPatterns(Context context) {
        List<Pattern> cached = compiledSuperiorPatterns;
        if (cached != null) return cached;
        synchronized (SUPERIOR_LOCK) {
            if (compiledSuperiorPatterns != null) return compiledSuperiorPatterns;
            List<Pattern> out = new ArrayList<>();
            for (String term : getSuperiorIngredientsList(context)) {
                out.add(Pattern.compile("\\b" + Pattern.quote(term) + "\\b"));
            }
            compiledSuperiorPatterns = out;
            return out;
        }
    }

    /**
     * Checks if a target ingredient token matches any superior ingredient listed in the asset file.
     * Uses regex word boundaries to avoid false positives.
     */
    public static boolean isSuperiorIngredient(Context context, String ingredient) {
        if (ingredient == null || ingredient.trim().isEmpty()) {
            return false;
        }

        List<Pattern> patterns = getSuperiorPatterns(context);
        if (patterns.isEmpty()) {
            return false;
        }

        String lowerCandidate = ingredient.toLowerCase(Locale.US).trim();
        for (Pattern pattern : patterns) {
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

    /**
     * Parses flagged_ingredients.json once per process and compiles every
     * term's pattern once. Returns an empty list (and logs once) when the
     * asset is missing — callers then fail open, loudly.
     */
    private static List<CompiledTerm> getCompiledTerms(Context context) {
        List<CompiledTerm> cached = compiledFlaggedTerms;
        if (cached != null) return cached;
        synchronized (FLAG_LOCK) {
            if (compiledFlaggedTerms != null) return compiledFlaggedTerms;
            List<CompiledTerm> terms = new ArrayList<>();
            Map<String, Boolean> defaults = new LinkedHashMap<>();
            if (context != null && context.getAssets() != null) {
                try (InputStream is = context.getAssets().open("flagged_ingredients.json");
                     BufferedReader reader = new BufferedReader(
                             new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    StringBuilder jsonBuilder = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        jsonBuilder.append(line);
                    }
                    JSONObject rootJson = new JSONObject(jsonBuilder.toString().trim());
                    Iterator<String> categoryKeys = rootJson.keys();
                    while (categoryKeys.hasNext()) {
                        String categoryName = categoryKeys.next();
                        JSONObject categoryObj = rootJson.optJSONObject(categoryName);
                        if (categoryObj == null) continue;
                        defaults.put(categoryName,
                                categoryObj.optBoolean("default_enabled", true));
                        JSONArray ingredientsArray = categoryObj.optJSONArray("ingredients");
                        if (ingredientsArray == null) continue;
                        for (int i = 0; i < ingredientsArray.length(); i++) {
                            String rawIngredient = ingredientsArray.optString(i, "").trim();
                            if (rawIngredient.isEmpty()) continue;
                            terms.add(new CompiledTerm(categoryName, rawIngredient,
                                    Pattern.compile(
                                            phrasePattern(rawIngredient.toLowerCase(Locale.US)))));
                        }
                    }
                } catch (Exception e) {
                    if (!flaggedLoadFailed) {
                        flaggedLoadFailed = true;
                        Log.e(TAG, "flagged_ingredients.json missing/unreadable:"
                                + " every product will read as CLEAN", e);
                    }
                }
            }
            flaggedCategoryDefaults = defaults;
            compiledFlaggedTerms = terms;
            return terms;
        }
    }

    /**
     * The set of flagged categories currently enabled by the user's sticky
     * toggles. Read once per search and passed to the pure
     * {@link #analyzeIngredients(String, Set)} — not per candidate.
     */
    public static Set<String> getEnabledCategories(Context context) {
        getCompiledTerms(context); // ensures defaults are loaded
        Map<String, Boolean> defaults = flaggedCategoryDefaults;
        if (defaults == null || defaults.isEmpty() || context == null) {
            return Collections.emptySet();
        }
        SharedPreferences prefs = defaultPrefs(context);
        Set<String> enabled = new HashSet<>();
        for (Map.Entry<String, Boolean> e : defaults.entrySet()) {
            if (prefs.getBoolean(e.getKey(), e.getValue())) {
                enabled.add(e.getKey());
            }
        }
        return enabled;
    }

    /**
     * Pure matching pass over the precompiled terms — no asset I/O, no
     * regex compilation, no preference reads. This is the hot path.
     */
    public static MatchResult analyzeIngredients(String rawIngredientsText,
                                                 Set<String> enabledCategories) {
        MatchResult result = new MatchResult();
        if (rawIngredientsText == null || rawIngredientsText.trim().isEmpty()) {
            return result;
        }
        List<CompiledTerm> terms = compiledFlaggedTerms;
        if (terms == null || terms.isEmpty()
                || enabledCategories == null || enabledCategories.isEmpty()) {
            return result;
        }
        String lowerCaseIngredients = rawIngredientsText.toLowerCase(Locale.US);
        for (CompiledTerm term : terms) {
            if (!enabledCategories.contains(term.category)) continue;
            if (term.pattern.matcher(lowerCaseIngredients).find()) {
                List<String> matched = result.categoryMap.get(term.category);
                if (matched == null) {
                    matched = new ArrayList<>();
                    result.categoryMap.put(term.category, matched);
                }
                if (!matched.contains(term.displayTerm)) {
                    matched.add(term.displayTerm);
                }
            }
        }
        return result;
    }

    /**
     * Compatibility wrapper: resolves the enabled categories on each call.
     * Prefer {@link #getEnabledCategories(Context)} once per search plus
     * {@link #analyzeIngredients(String, Set)} per candidate.
     */
    public static MatchResult analyzeIngredients(Context context, String rawIngredientsText) {
        if (context != null) getCompiledTerms(context);
        return analyzeIngredients(rawIngredientsText, getEnabledCategories(context));
    }
}
