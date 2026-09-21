package com.example.dirtyingredients;

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

    private static final String API_KEY = BuildConfig.USDA_API_KEY;

    @Override
    public List<String> fetchSuggestions(String query) throws Exception {
        List<String> suggestions = new ArrayList<>();

        if (query == null || query.trim().length() < 2) {
            return suggestions;
        }

        URL url = new URL("https://api.nal.usda.gov/fdc/v1/foods/search?api_key=" + API_KEY);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(2000);
        c.setReadTimeout(2000);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setDoOutput(true);

        JSONObject jsonPayload = new JSONObject();
        jsonPayload.put("query", query.trim());
        jsonPayload.put("dataType", new JSONArray(List.of("Branded")));
        jsonPayload.put("pageSize", 8);
        jsonPayload.put("fields", new JSONArray(List.of("description", "brandOwner")));

        try (OutputStream os = c.getOutputStream()) {
            byte[] inputBytes = jsonPayload.toString().getBytes(StandardCharsets.UTF_8);
            os.write(inputBytes, 0, inputBytes.length);
        }

        if (c.getResponseCode() == 200) {
            try (InputStream is = c.getInputStream()) {
                String body = readAll(is);
                JSONObject root = new JSONObject(body);
                JSONArray foods = root.optJSONArray("foods");

                if (foods != null) {
                    for (int i = 0; i < foods.length(); i++) {
                        JSONObject item = foods.getJSONObject(i);
                        String description = item.optString("description", "");
                        String brand = item.optString("brandOwner", "");

                        String label = brand.isEmpty() ? description : description + " (" + brand + ")";
                        if (!suggestions.contains(label)) {
                            suggestions.add(label);
                        }
                    }
                }
            }
        }
        c.disconnect();
        return suggestions;
    }

    private String readAll(InputStream is) {
        Scanner s = new Scanner(is, StandardCharsets.UTF_8).useDelimiter("\\A");
        return s.hasNext() ? s.next() : "";
    }
}
