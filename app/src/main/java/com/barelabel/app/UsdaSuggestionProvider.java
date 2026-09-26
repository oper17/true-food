package com.barelabel.app;

import android.content.Context;
import android.util.Log;

import com.barelabel.app.model.Suggestion;
import com.barelabel.app.search.UsdaSpamFilter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;

public class UsdaSuggestionProvider implements SuggestionProvider {

    private static final String TAG = "UsdaSuggestionProvider";
    private static final String API_KEY = BuildConfig.USDA_API_KEY;
    private final Context context;

    public UsdaSuggestionProvider(Context context) {
        this.context = context;
    }

    @Override
    public List<Suggestion> fetchSuggestions(String query) throws Exception {
        List<Suggestion> suggestions = new ArrayList<>();

        if (query == null || query.trim().length() < 2) {
            return suggestions;
        }

        String trimmedQuery = query.trim();
        // v2: larger page + spam filtering (see UsdaSpamFilter); old cached
        // v1 responses are unfiltered, so they must not be reused.
        String cacheKey = "usda_suggestions_v2_" + trimmedQuery.toLowerCase();

        // 1. Check local disk cache (7-day TTL)
        String body = null;
        if (context != null) {
            body = UsdaResponseCache.get(context, cacheKey);
        }

        // 2. Fetch from network if cache missed or expired
        if (body == null) {
            URL url = new URL("https://api.nal.usda.gov/fdc/v1/foods/search?api_key=" + API_KEY);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(2000);
            c.setReadTimeout(2000);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);

            JSONObject jsonPayload = new JSONObject();
            jsonPayload.put("query", trimmedQuery);
            
            JSONArray dataTypes = new JSONArray();
            dataTypes.put("Branded");
            jsonPayload.put("dataType", dataTypes);
            // 25 for re-ranking headroom: spam is filtered client-side and
            // only the top 8 survivors are shown.
            jsonPayload.put("pageSize", 25);

            JSONArray fields = new JSONArray();
            fields.put("fdcId");
            fields.put("description");
            fields.put("brandOwner");
            // brandName is the food-label brand (SIMPLY BALANCED); without
            // it in fields the display falls back to the corporate owner.
            fields.put("brandName");
            jsonPayload.put("fields", fields);

            try (OutputStream os = c.getOutputStream()) {
                byte[] inputBytes = jsonPayload.toString().getBytes(StandardCharsets.UTF_8);
                os.write(inputBytes, 0, inputBytes.length);
            }

            if (c.getResponseCode() == 200) {
                try (InputStream is = c.getInputStream()) {
                    body = readAll(is);
                    // Cache the successful network response
                    if (context != null && body != null && !body.isEmpty()) {
                        UsdaResponseCache.put(context, cacheKey, body);
                    }
                }
            } else {
                Log.e(TAG, "USDA Suggestion request failed with status: " + c.getResponseCode());
            }
            c.disconnect();
        }

        // 3. Parse JSON response (from either cache or network)
        if (body != null && !body.isEmpty()) {
            JSONObject root = new JSONObject(body);
            JSONArray foods = root.optJSONArray("foods");

            if (foods != null) {
                // Rank (whole-word matches first, brand-spam last), drop
                // spam, and dedupe by description — eight identical
                // "BREAD (obscure brand)" rows help nobody.
                List<JSONObject> ranked =
                        UsdaSpamFilter.rankedCandidates(foods, trimmedQuery);
                Set<String> seenDescriptions = new HashSet<String>();
                for (JSONObject item : ranked) {
                    if (suggestions.size() >= 8) {
                        break;
                    }
                    String description = item.optString("description", "");
                    String owner = item.optString("brandOwner", "");
                    if (UsdaSpamFilter.isSpamBrand(owner)) {
                        continue;
                    }
                    // Suggest the food-label brand; the owner stays only as
                    // the spam signal above, not the display text.
                    String labelBrand = item.optString("brandName", "");
                    String brand = !labelBrand.isEmpty() ? labelBrand : owner;
                    String descKey = UsdaSpamFilter.normalize(description);
                    if (!descKey.isEmpty() && !seenDescriptions.add(descKey)) {
                        continue;
                    }
                    long fdcId = item.optLong("fdcId", 0);

                    String label = brand.isEmpty() ? description : description + " (" + brand + ")";
                    boolean known = false;
                    for (Suggestion s : suggestions) {
                        if (s.label.equals(label)) { known = true; break; }
                    }
                    if (!known) {
                        suggestions.add(new Suggestion(label, fdcId));
                    }
                }
            }
        }

        return suggestions;
    }

    private String readAll(InputStream is) {
        Scanner s = new Scanner(is, StandardCharsets.UTF_8).useDelimiter("\\A");
        return s.hasNext() ? s.next() : "";
    }
}
