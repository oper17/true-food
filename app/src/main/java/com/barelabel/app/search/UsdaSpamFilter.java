package com.barelabel.app.search;

import com.barelabel.app.util.StringNormalizer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Filters USDA FoodData Central spam out of branded search results.
 *
 * The Branded database contains records filed under obviously-fake,
 * non-food brand owners ("Multicom Publishing Incorporated",
 * "Bang Brothers Entertainment, Inc") with a bare generic description
 * ("BREAD"). USDA ranks exact-description matches first, so a generic
 * query's top results are all spam — e.g. searching "bread" returned
 * eight identical-looking "BREAD (junk brand)" suggestions.
 *
 * Two fixes, applied wherever USDA search results surface:
 *  1. Brand-spam filter: records whose brand owner is a non-food company
 *     are demoted to the end of the results (callers skip them).
 *  2. Whole-word ranking: results whose description contains the query as
 *     a whole word ("ciabatta bread" for "bread") outrank substring
 *     matches ("breaded calamari").
 */
public final class UsdaSpamFilter {

    /** Brand-owner words that mark a record as definite non-food spam. */
    private static final Set<String> NON_FOOD_BRAND_WORDS = new HashSet<String>();
    static {
        NON_FOOD_BRAND_WORDS.add("publishing");
        NON_FOOD_BRAND_WORDS.add("software");
        NON_FOOD_BRAND_WORDS.add("entertainment");
        NON_FOOD_BRAND_WORDS.add("media");
        NON_FOOD_BRAND_WORDS.add("films");
    }

    private UsdaSpamFilter() {
    }

    /** Null-safe normalization (lowercase, punctuation stripped). */
    public static String normalize(String s) {
        return s == null ? "" : StringNormalizer.normalize(s);
    }

    /** True when the brand owner is an obvious non-food company (spam). */
    public static boolean isSpamBrand(String brandOwner) {
        String padded = " " + normalize(brandOwner) + " ";
        for (String w : NON_FOOD_BRAND_WORDS) {
            if (padded.contains(" " + w + " ")) {
                return true;
            }
        }
        return false;
    }

    /** True when the description contains the query as a whole word. */
    public static boolean isWholeWordMatch(String description, String query) {
        return isWholeWordMatchNormalized(description, normalize(query));
    }

    /** Whole-word match against an already-normalized query (normalize once per result set). */
    private static boolean isWholeWordMatchNormalized(String description, String normalizedQuery) {
        if (normalizedQuery.isEmpty()) {
            return false;
        }
        return (" " + normalize(description) + " ").contains(" " + normalizedQuery + " ");
    }

    /**
     * Stable reorder of raw USDA results: whole-word non-spam matches
     * first, then other non-spam, then brand-spam last. Callers skip the
     * spam tail via {@link #isSpamBrand(String)}.
     */
    public static List<JSONObject> rankedCandidates(JSONArray foods, String query) {
        String normalizedQuery = normalize(query);
        List<JSONObject> whole = new ArrayList<JSONObject>();
        List<JSONObject> rest = new ArrayList<JSONObject>();
        List<JSONObject> spam = new ArrayList<JSONObject>();
        if (foods != null) {
            for (int i = 0; i < foods.length(); i++) {
                JSONObject f = foods.optJSONObject(i);
                if (f == null) {
                    continue;
                }
                if (isSpamBrand(f.optString("brandOwner", ""))) {
                    spam.add(f);
                } else if (isWholeWordMatchNormalized(f.optString("description", ""), normalizedQuery)) {
                    whole.add(f);
                } else {
                    rest.add(f);
                }
            }
        }
        List<JSONObject> out =
                new ArrayList<JSONObject>(whole.size() + rest.size() + spam.size());
        out.addAll(whole);
        out.addAll(rest);
        out.addAll(spam);
        return out;
    }
}
