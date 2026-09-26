package com.barelabel.app.network;

import android.content.Context;
import android.text.TextUtils;

import com.barelabel.app.BuildConfig;
import com.barelabel.app.FlaggedIngredientManager;
import com.barelabel.app.UsdaResponseCache;
import com.barelabel.app.model.ProductResult;
import com.barelabel.app.search.UsdaSpamFilter;

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
import java.util.List;

/**
 * All USDA FoodData Central HTTP traffic: the primary product search, paged
 * alternates/category lookups, and GTIN lookup. Responses flow through the
 * 7-day disk cache (UsdaResponseCache).
 */
public class UsdaApiClient {

    private static final String BASE_URL = "https://api.nal.usda.gov/fdc/v1/foods/search";
    private static final String USER_AGENT =
            "BareLabel/1.0 (Android food ingredient screening app)";

    /** Retry policy for idempotent USDA calls: 3 attempts, exponential backoff. */
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_BASE_MS = 500;
    private static final long RETRY_MAX_MS = 8000;
    private static final java.util.regex.Pattern WS_UNDERSCORE =
            java.util.regex.Pattern.compile("\\s+");

    private final Context appContext;

    public UsdaApiClient(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /** Builds a configured connection; the request body (if any) is written inside open(). */
    private interface ConnectionFactory {
        HttpURLConnection open() throws IOException;
    }

    /**
     * Executes an idempotent USDA request with exponential backoff on
     * transient failures: 429 (honoring Retry-After), 5xx, and transport
     * errors. Other 4xx fail fast. 2xx returns the body; persistent
     * failures throw with the server's error body attached.
     */
    private String executeWithRetry(ConnectionFactory factory) throws IOException {
        long backoffMs = RETRY_BASE_MS;
        IOException failure = null;
        boolean permanent = false;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS && !permanent; attempt++) {
            HttpURLConnection c = null;
            try {
                c = factory.open();
                int code = c.getResponseCode();
                String body = readResponseBody(c, code);
                if (code >= 200 && code < 300) {
                    return body;
                }
                failure = new IOException("HTTP " + code + bodySnippet(body));
                if (code == 429 || code >= 500) {
                    if (attempt < MAX_ATTEMPTS) {
                        backoffMs = sleepBeforeRetry(c, backoffMs);
                    }
                } else {
                    permanent = true;
                }
            } catch (IOException e) {
                // Abort immediately when the thread was interrupted.
                if (Thread.currentThread().isInterrupted()) throw e;
                failure = e;
                if (attempt < MAX_ATTEMPTS) {
                    backoffMs = sleepBeforeRetry(c, backoffMs);
                }
            } finally {
                if (c != null) c.disconnect();
            }
        }
        throw failure != null ? failure : new IOException("USDA request failed");
    }

    /** Sleeps before the next attempt; 429 honors the server's Retry-After. */
    private long sleepBeforeRetry(HttpURLConnection c, long backoffMs) throws IOException {
        long waitMs = backoffMs;
        if (c != null) {
            String retryAfter = c.getHeaderField("Retry-After");
            if (retryAfter != null) {
                try {
                    waitMs = Math.max(waitMs, Long.parseLong(retryAfter.trim()) * 1000L);
                } catch (NumberFormatException ignored) {}
            }
        }
        try {
            Thread.sleep(waitMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("retry backoff interrupted");
        }
        return Math.min(backoffMs * 2, RETRY_MAX_MS);
    }

    private String getWithRetry(final String urlString, final int connectTimeout,
                                final int readTimeout) throws IOException {
        return executeWithRetry(() -> {
            HttpURLConnection c = (HttpURLConnection) new URL(urlString).openConnection();
            c.setConnectTimeout(connectTimeout);
            c.setReadTimeout(readTimeout);
            c.setRequestMethod("GET");
            c.setRequestProperty("User-Agent", USER_AGENT);
            return c;
        });
    }

    private static String readResponseBody(HttpURLConnection c, int code) throws IOException {
        InputStream raw = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        if (raw == null) return "";
        try (InputStream is = raw) {
            return readAll(is);
        }
    }

    /** First ~200 chars of an error body, flattened — for exception messages. */
    private static String bodySnippet(String body) {
        if (body == null || body.isEmpty()) return "";
        String flat = body.replaceAll("\\s+", " ").trim();
        return ": " + flat.substring(0, Math.min(200, flat.length()));
    }

    /**
     * Primary branded-product search for the user's query. Never null;
     * returns ProductResult.notFound() when USDA has nothing usable.
     */
    public ProductResult searchPrimary(String productName) throws Exception {
        // v4: larger page + spam filtering (see UsdaSpamFilter); old cached
        // v3 responses are unfiltered, so they must not be reused.
        String cacheKey = "usda_search4_" + productName.toLowerCase().trim();

        // 1. Check local disk cache (7-day TTL)
        String body = UsdaResponseCache.get(appContext, cacheKey);

        // 2. Fetch from network if cache missed or expired (with retry)
        if (body == null) {
            String q = URLEncoder.encode(productName, "UTF-8");

            final String urlString = BASE_URL
                    + "?api_key=" + BuildConfig.USDA_API_KEY
                    + "&query=" + q
                    + "&dataType=Branded"
                    + "&pageSize=25";

            body = getWithRetry(urlString, 10000, 15000);

            // Save valid network response to cache
            if (!TextUtils.isEmpty(body)) {
                UsdaResponseCache.put(appContext, cacheKey, body);
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

        // Rank: whole-word matches first, brand-spam last. Skip spam when
        // choosing — generic queries ("bread") otherwise land on fake
        // records filed under non-food companies.
        List<JSONObject> ranked = UsdaSpamFilter.rankedCandidates(foods, productName);
        JSONObject chosen = null;
        for (JSONObject f : ranked) {
            if (UsdaSpamFilter.isSpamBrand(f.optString("brandOwner", ""))) {
                continue;
            }
            String ingredients = f.optString("ingredients", "");
            if (!TextUtils.isEmpty(ingredients.trim())) {
                chosen = f;
                break;
            }
        }
        if (chosen == null) {
            for (JSONObject f : ranked) {
                if (!UsdaSpamFilter.isSpamBrand(f.optString("brandOwner", ""))) {
                    chosen = f;
                    break;
                }
            }
        }
        if (chosen == null) chosen = foods.getJSONObject(0);

        return buildProductResult(chosen, productName);
    }

    /**
     * Fetches one USDA record by its fdcId (the exact record behind a tapped
     * autocomplete suggestion). Never null; returns ProductResult.notFound()
     * when the record cannot be retrieved.
     */
    public ProductResult fetchFoodById(long fdcId) throws Exception {
        String cacheKey = "usda_food_" + fdcId;

        // 1. Check local disk cache (7-day TTL)
        String body = UsdaResponseCache.get(appContext, cacheKey);

        // 2. Fetch from network if cache missed or expired (with retry)
        if (body == null) {
            final String urlString = "https://api.nal.usda.gov/fdc/v1/food/" + fdcId
                    + "?api_key=" + BuildConfig.USDA_API_KEY;

            body = getWithRetry(urlString, 10000, 15000);

            // Save valid network response to cache
            if (!TextUtils.isEmpty(body)) {
                UsdaResponseCache.put(appContext, cacheKey, body);
            }
        }

        if (TextUtils.isEmpty(body)) {
            return ProductResult.notFound();
        }

        return buildProductResult(new JSONObject(body), "");
    }

    /** Builds a ProductResult from one USDA food object (search hit or /food record). */
    private ProductResult buildProductResult(JSONObject food, String fallbackName) {
        String name = food.optString("description", fallbackName);
        String brandName = food.optString("brandName", "");
        String brandOwner = food.optString("brandOwner", "");
        // Show the brand on the food label (what shoppers recognize); the
        // brand owner is only a fallback — one owner often holds many
        // unrelated brands ("Post Consumer Brands" vs "Oreo O's").
        String brand = !brandName.isEmpty() ? brandName : brandOwner;
        String ingredients = food.optString("ingredients", "");

        // Process ingredients with FlaggedIngredientManager (JSON Engine)
        FlaggedIngredientManager.MatchResult matchResult =
                FlaggedIngredientManager.analyzeIngredients(appContext, ingredients);
        ProductResult result = new ProductResult(true, name, brand, ingredients, matchResult);
        // USDA's own category + identifiers for this product (verified on /foods/search)
        result.foodCategory = food.optString("foodCategory", "");
        result.gtinUpc = food.optString("gtinUpc", "");
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
        // The category is part of the key: without it, a cached unfiltered
        // page could be served for a filtered request (or vice versa).
        String cacheKey = "usda_alternates_"
                + (filterByCategory ? "cat_" + foodCategory + "_" : "")
                + WS_UNDERSCORE.matcher(query.toLowerCase().trim()).replaceAll("_")
                + "_p" + pageNumber;

        // 1. Check local disk cache (7-day TTL)
        String body = UsdaResponseCache.get(appContext, cacheKey);

        // 2. Fetch from network if cache missed or expired (with retry).
        // Persistent failures throw; callers treat that as end-of-pages.
        if (body == null) {
            final JSONObject payload = new JSONObject();
            payload.put("query", query);
            payload.put("dataType", new JSONArray().put("Branded"));
            payload.put("pageSize", pageSize);
            payload.put("pageNumber", pageNumber);
            if (filterByCategory) {
                // Narrow results using the USDA's own category vocabulary.
                payload.put("foodCategory", foodCategory);
            }
            final byte[] payloadBytes = payload.toString().getBytes(StandardCharsets.UTF_8);

            body = executeWithRetry(() -> {
                HttpURLConnection c = (HttpURLConnection) new URL(
                        BASE_URL + "?api_key=" + BuildConfig.USDA_API_KEY).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("User-Agent", USER_AGENT);
                c.setDoOutput(true);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(payloadBytes);
                }
                return c;
            });

            // Save valid network response to cache
            if (!TextUtils.isEmpty(body)) {
                UsdaResponseCache.put(appContext, cacheKey, body);
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
        // GTIN lookups are repeated across barcode scans; cache the raw
        // response instead of hitting USDA twice per scan.
        String cacheKey = "usda_gtin_" + gtin.replaceAll("\\D", "");

        String body = UsdaResponseCache.get(appContext, cacheKey);
        if (body == null) {
            final String urlString = BASE_URL + "?query="
                    + URLEncoder.encode(gtin, "UTF-8")
                    + "&dataType=Branded"
                    + "&pageSize=10"
                    + "&api_key=" + BuildConfig.USDA_API_KEY;

            body = getWithRetry(urlString, 8000, 10000);
            if (!TextUtils.isEmpty(body)) {
                UsdaResponseCache.put(appContext, cacheKey, body);
            }
        }

        if (TextUtils.isEmpty(body)) return null;

        JSONObject responseJson = new JSONObject(body);
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
    }

    private static String readAll(InputStream is) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder b = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) b.append(line).append('\n');
        r.close();
        return b.toString();
    }
}
