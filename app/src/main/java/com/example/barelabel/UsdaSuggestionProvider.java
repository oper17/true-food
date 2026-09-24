package com.example.barelabel;

import android.content.Context;
import android.util.Log;

import com.example.barelabel.model.Suggestion;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

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
        String cacheKey = "usda_suggestions_" + trimmedQuery.toLowerCase();

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
            jsonPayload.put("pageSize", 8);

            JSONArray fields = new JSONArray();
            fields.put("fdcId");
            fields.put("description");
            fields.put("brandOwner");
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
                for (int i = 0; i < foods.length(); i++) {
                    JSONObject item = foods.getJSONObject(i);
                    String description = item.optString("description", "");
                    String brand = item.optString("brandOwner", "");
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
