package com.barelabel.app.search;

import android.content.Context;
import android.text.TextUtils;

import com.barelabel.app.FlaggedIngredientManager;
import com.barelabel.app.model.AlternateSearchResult;
import com.barelabel.app.model.ProductResult;
import com.barelabel.app.network.UsdaApiClient;
import com.barelabel.app.util.StringNormalizer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * Finds clean (unflagged) USDA alternates for a product or a category.
 * <p>
 * Clean items can be rare in a category (e.g. almost every cookie contains
 * wheat), so every page is scanned into a full clean pool instead of stopping
 * at the first few hits — USDA sorts by relevance, not organic-ness, and
 * organic items are a small minority that would otherwise never be seen.
 * A second, explicit {@code "organic <category>"} query hunts organic
 * versions, since USDA full-text search matches "organic" against the
 * description, ingredients, and brand. For category (unbranded) searches the
 * pool is seeded with the user's raw query first, so the strict all-tokens
 * exact filter has query-relevant items to match (e.g. "cream cheese"
 * classifies to the broad "Cheese" category).
 */
public class CleanAlternateFinder {

    private static final int PAGE_SIZE = 50;
    private static final int MAX_POOL_SIZE = 60;

    private final Context appContext;
    private final UsdaApiClient apiClient;

    public CleanAlternateFinder(Context context, UsdaApiClient apiClient) {
        this.appContext = context.getApplicationContext();
        this.apiClient = apiClient;
    }

    public AlternateSearchResult findCleanAlternates(String foodCategory, boolean filterByCategory,
                                                     boolean categoryIntent, ProductResult primaryResult,
                                                     String userQuery)
            throws Exception {
        AlternateSearchResult result = new AlternateSearchResult();

        if (!primaryResult.found
                || primaryResult.ingredients == null
                || primaryResult.ingredients.trim().isEmpty()
                || foodCategory == null
                || "Uncategorized".equalsIgnoreCase(foodCategory)) {
            return result;
        }

        // Clean alternates are normally fetched only for dirty products; a category
        // (unbranded) search always wants clean choices, even if the top hit is clean.
        if (!categoryIntent && primaryResult.flagged.isEmpty()) {
            return result;
        }

        Set<String> seenProductKeys = new HashSet<>();
        String primaryKey = StringNormalizer.normalize(primaryResult.name)
                + "|" + StringNormalizer.normalize(primaryResult.brand);
        seenProductKeys.add(primaryKey);

        // For a category (unbranded) search, seed the pool with the user's actual
        // query first. The category pass below is broad ("cream cheese" classifies
        // to "Cheese"), so without this the pool would hold generic category items
        // and the strict all-tokens exact filter would rarely match anything.
        if (categoryIntent && userQuery != null && !userQuery.trim().isEmpty()
                && !userQuery.trim().equalsIgnoreCase(foodCategory)) {
            collectCleanAlternates(result, seenProductKeys, foodCategory,
                    false, userQuery.trim(), 2);
        }

        collectCleanAlternates(result, seenProductKeys, foodCategory,
                filterByCategory, foodCategory, 3);

        // Organic versions exist in the database but rarely crack relevance-sorted
        // results, so hunt for them explicitly.
        collectCleanAlternates(result, seenProductKeys, foodCategory,
                filterByCategory, "organic " + foodCategory, 2);

        return result;
    }

    /**
     * Pages through USDA search results for one query, adding every clean
     * (unflagged) branded product to the pool. The pool is capped to keep the
     * local ranking cheap; the UI still shows only the top-ranked few.
     */
    private void collectCleanAlternates(AlternateSearchResult result, Set<String> seenProductKeys,
                                        String foodCategory, boolean filterByCategory,
                                        String query, int maxPages) throws Exception {
        int pageNumber = 1;
        while (result.alternates.size() < MAX_POOL_SIZE && pageNumber <= maxPages) {
            JSONArray foods = apiClient.fetchFoodsPage(query, foodCategory, filterByCategory,
                    pageNumber, PAGE_SIZE);
            if (foods == null || foods.length() == 0) {
                break;
            }
            for (int i = 0; i < foods.length(); i++) {
                JSONObject f = foods.getJSONObject(i);
                String name = f.optString("description", "");
                String brand = f.optString("brandOwner", f.optString("brandName", ""));
                String ingredients = f.optString("ingredients", "");

                if (TextUtils.isEmpty(ingredients.trim())) {
                    continue;
                }

                String productKey = StringNormalizer.normalize(name)
                        + "|" + StringNormalizer.normalize(brand);
                if (seenProductKeys.contains(productKey)) {
                    continue;
                }
                seenProductKeys.add(productKey);

                FlaggedIngredientManager.MatchResult matchResult =
                        FlaggedIngredientManager.analyzeIngredients(appContext, ingredients);
                if (matchResult != null && matchResult.hasMatches()) {
                    // Remember which categories blocked this candidate so the UI can
                    // explain an empty result ("everything here contains gluten…").
                    Set<String> blockedBy = matchResult.categoryMap.keySet();
                    result.flaggedCategories.addAll(blockedBy);
                    // Candidates blocked by exactly one filter are the honest basis
                    // for "uncheck X to bring back N items" suggestions.
                    if (blockedBy.size() == 1) {
                        String only = blockedBy.iterator().next();
                        Integer n = result.singleFilterBlockCounts.get(only);
                        result.singleFilterBlockCounts.put(only, n == null ? 1 : n + 1);
                    }
                    continue;
                }
                ProductResult altResult = new ProductResult(true, name, brand, ingredients, matchResult);
                altResult.foodCategory = f.optString("foodCategory", "");
                altResult.gtinUpc = f.optString("gtinUpc", "");
                altResult.brandName = f.optString("brandName", "");
                altResult.brandOwner = f.optString("brandOwner", "");
                result.alternates.add(altResult);

                if (result.alternates.size() >= MAX_POOL_SIZE) {
                    break;
                }
            }
            if (foods.length() < PAGE_SIZE) {
                break; // last page
            }
            pageNumber++;
        }
    }
}
