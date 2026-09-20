package com.example.dirtyingredients;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class UsdaAutoCompleteManager {

    private static final String API_KEY = BuildConfig.USDA_API_KEY;
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private ArrayAdapter<String> adapter;
    private Runnable searchRunnable;
    private boolean isSelectingFromList = false; // Flag to stop re-opening on click

    public UsdaAutoCompleteManager(Context context) {
        this.context = context;
    }

    public void attachToTextView(AutoCompleteTextView autoCompleteTextView) {
        adapter = new ArrayAdapter<>(context, android.R.layout.simple_dropdown_item_1line, new ArrayList<>());
        autoCompleteTextView.setAdapter(adapter);

        // Handle item selection: close drop-down and set flag
        autoCompleteTextView.setOnItemClickListener((parent, view, position, id) -> {
            isSelectingFromList = true; // Block TextWatcher from running search
            if (searchRunnable != null) {
                handler.removeCallbacks(searchRunnable); // Cancel pending searches
            }
            autoCompleteTextView.dismissDropDown();
        });

        autoCompleteTextView.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (searchRunnable != null) {
                    handler.removeCallbacks(searchRunnable);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
                // If text changed because user selected an item, skip API call
                if (isSelectingFromList) {
                    isSelectingFromList = false; // Reset flag for next manual keystroke
                    return;
                }

                String query = s.toString().trim();

                if (query.length() < 2) {
                    updateSuggestionsList(new ArrayList<>(), autoCompleteTextView);
                    return;
                }

                searchRunnable = () -> executor.execute(() -> {
                    try {
                        List<String> suggestions = fetchUsdaSuggestions(query);
                        handler.post(() -> updateSuggestionsList(suggestions, autoCompleteTextView));
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });

                handler.postDelayed(searchRunnable, 200);
            }
        });
    }

    private void updateSuggestionsList(List<String> suggestions, AutoCompleteTextView autoCompleteTextView) {
        adapter.clear();
        // Do not display if user has since closed the dropdown or selected an item
        if (suggestions != null && !suggestions.isEmpty() && !isSelectingFromList) {
            adapter.addAll(suggestions);
            adapter.notifyDataSetChanged();
            autoCompleteTextView.showDropDown();
        } else {
            adapter.notifyDataSetInvalidated();
        }
    }

    private List<String> fetchUsdaSuggestions(String input) throws Exception {
        List<String> suggestions = new ArrayList<>();

        URL url = new URL("https://api.nal.usda.gov/fdc/v1/foods/search?api_key=" + API_KEY);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(2000);
        c.setReadTimeout(2000);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setDoOutput(true);

        JSONObject jsonPayload = new JSONObject();
        jsonPayload.put("query", input);
        jsonPayload.put("dataType", new JSONArray(List.of("Branded")));
        jsonPayload.put("pageSize", 8);
        jsonPayload.put("fields", new JSONArray(List.of("description", "brandOwner")));

        try (OutputStream os = c.getOutputStream()) {
            byte[] inputBytes = jsonPayload.toString().getBytes(StandardCharsets.UTF_8);
            os.write(inputBytes, 0, inputBytes.length);
        }

        if (c.getResponseCode() == 200) {
            InputStream is = c.getInputStream();
            String body = readAll(is);
            c.disconnect();

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
        } else {
            c.disconnect();
        }

        return suggestions;
    }

    private String readAll(InputStream is) throws Exception {
        java.util.Scanner s = new java.util.Scanner(is, "UTF-8").useDelimiter("\\A");
        String result = s.hasNext() ? s.next() : "";
        is.close();
        return result;
    }
}
