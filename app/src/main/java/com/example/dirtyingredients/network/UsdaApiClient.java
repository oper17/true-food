package com.example.dirtyingredients.network;

import android.content.Context;
import android.text.TextUtils;

import com.example.dirtyingredients.BuildConfig;
import com.example.dirtyingredients.FlaggedIngredientManager;
import com.example.dirtyingredients.UsdaResponseCache;
import com.example.dirtyingredients.model.ProductResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * All USDA FoodData Central HTTP traffic: the primary product search, paged
 * alternates/category lookups, and GTIN lookup. Responses flow through the
 * 7-day disk cache (UsdaResponseCache).
 */
public class UsdaApiClient {

    private static final String BASE_URL = "https://api.nal.usda.gov/fdc/v1/foods/search";
    private static final String USER_AGENT =
            "DirtyIngredients/1.0 (Android food ingredient screening app)";

    private final Context appContext;

    public UsdaApiClient(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /**
     * Primary branded-product search for the user's query. Never null;
     * returns ProductResult.notFound() when USDA has nothing usable.
     */
    public ProductResult searchPrimary(String productName) throws Exception {
        String cacheKey = "usda_search3_" + productName.toLowerCase().trim();

        // 1. Check local disk cache (7-day TTL)
        String body = UsdaResponseCache.get(appContext, cacheKey);

        // 2. Fetch from network if cache missed or expired
        if (body == null) {
            String q = URLEncoder.encode(productName, "UTF-8");

            String url = BASE_URL
                    + "?api_key=" + BuildConfig.USDA_API_KEY
                    + "&query=" + q
                    + "&dataType=Branded"
                    + "&pageSize=10";

            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            try {
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                c.setRequestMethod("GET");
                c.setRequestProperty("User-Agent", USER_AGENT);

                int code = c.getResponseCode();
                try (InputStream is = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream()) {
                    body = readAll(is);
                }

                if (code < 200 || code >= 300) {
                    throw new IOException("HTTP " + code);
                }

                // Save valid network response to cache
                if (!TextUtils.isEmpty(body)) {
                    UsdaResponseCache.put(appContext, cacheKey, body);
                }
            } finally {
                c.disconnect();
            }
        }

        if (TextUtils.isEmpty(body)) {
            return ProductResult.notFound();
        }

        JSONObject root = new JSONObject(body);
        JSONArray foods = root.optJSONArray("foods");
        if (foods == null || foods.length() == 0) {
            return ProductResult.notFound();
        }

        JSONObject chosen = null;
        for (int i = 0; i < foods.length(); i++) {
            JSONObject f = foods.getJSONObject(i);
            String ingredients = f.optString("ingredients", "");
            if (!TextUtils.isEmpty(ingredients.trim())) {
                chosen = f;
                break;
            }
        }
        if (chosen == null) chosen = foods.getJSONObject(0);

        String name = chosen.optString("description", productName);
        String brandName = chosen.optString("brandName", "");
        String brandOwner = chosen.optString("brandOwner", "");
        String brand = !brandOwner.isEmpty() ? brandOwner : brandName;
        String ingredients = chosen.optString("ingredients", "");

        // Process ingredients with FlaggedIngredientManager (JSON Engine)
        FlaggedIngredientManager.MatchResult matchResult =
                FlaggedIngredientManager.analyzeIngredients(appContext, ingredients);
        ProductResult result = new ProductResult(true, name, brand, ingredients, matchResult);
        // USDA's own category + identifiers for this product (verified on /foods/search)
        result.foodCategory = chosen.optString("foodCategory", "");
        result.gtinUpc = chosen.optString("gtinUpc", "");
        result.brandName = brandName;
        result.brandOwner = brandOwner;
        return result;
    }

    /**
     * Fetches one page of branded products for the alternates/category lookup,
     * using the 7-day disk cache when available.
     */
    public JSONArray fetchFoodsPage(String query, String foodCategory, boolean filterByCategory,
                                    int pageNumber, int pageSize) throws Exception {
        String cacheKey = "usda_alternates_" + (filterByCategory ? "cat_" : "")
                + query.toLowerCase().trim().replaceAll("\\s+", "_") + "_p" + pageNumber;

        // 1. Check local disk cache (7-day TTL)
        String body = UsdaResponseCache.get(appContext, cacheKey);

        // 2. Fetch from network if cache missed or expired
        if (body == null) {
            HttpURLConnection c = (HttpURLConnection) new URL(
                    BASE_URL + "?api_key=" + BuildConfig.USDA_API_KEY).openConnection();
            try {
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("User-Agent", USER_AGENT);
                c.setDoOutput(true);

                JSONObject payload = new JSONObject();
                payload.put("query", query);
                payload.put("dataType", new JSONArray().put("Branded"));
                payload.put("pageSize", pageSize);
                payload.put("pageNumber", pageNumber);
                if (filterByCategory) {
                    // Narrow results using the USDA's own category vocabulary.
                    payload.put("foodCategory", foodCategory);
                }

                try (OutputStream os = c.getOutputStream()) {
                    os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
                }

                int code = c.getResponseCode();
                try (InputStream is = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream()) {
                    body = readAll(is);
                }

                if (code < 200 || code >= 300) {
                    return null;
                }

                // Save valid network response to cache
                if (!TextUtils.isEmpty(body)) {
                    UsdaResponseCache.put(appContext, cacheKey, body);
                }
            } finally {
                c.disconnect();
            }
        }

        if (TextUtils.isEmpty(body)) {
            return null;
        }

        JSONObject root = new JSONObject(body);
        return root.optJSONArray("foods");
    }

    /**
     * Queries USDA for a GTIN and returns the food whose gtinUpc matches exactly
     * (comparing without leading zeros), or null when nothing matches. Falls back
     * to the first branded result with an ingredient list when no exact gtinUpc
     * match exists.
     */
    public JSONObject findFoodByGtin(String gtin) throws Exception {
        String urlString = BASE_URL + "?query="
                + URLEncoder.encode(gtin, "UTF-8")
                + "&dataType=Branded"
                + "&pageSize=10"
                + "&api_key=" + BuildConfig.USDA_API_KEY;

        HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", USER_AGENT);

            if (conn.getResponseCode() != 200) return null;

            JSONObject responseJson = new JSONObject(readAll(conn.getInputStream()));
            JSONArray foods = responseJson.optJSONArray("foods");
            if (foods == null || foods.length() == 0) return null;

            String needle = gtin.replaceFirst("^0+", "");
            JSONObject fallback = null;
            for (int i = 0; i < foods.length(); i++) {
                JSONObject f = foods.getJSONObject(i);
                String stored = f.optString("gtinUpc", "").replaceFirst("^0+", "");
                if (!stored.isEmpty() && stored.equals(needle)) {
                    return f; // exact GTIN match
                }
                if (fallback == null && !f.optString("ingredients", "").trim().isEmpty()) {
                    fallback = f;
                }
            }
            return fallback;
        } finally {
            conn.disconnect();
        }
    }

    private String readAll(InputStream is) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder b = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) b.append(line).append('\n');
        r.close();
        return b.toString();
    }
}
