package com.barelabel.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Filter;

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

    private static final String TAG = "UsdaAutoCompleteManager";
    private static final String API_KEY = BuildConfig.USDA_API_KEY;
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private ArrayAdapter<String> adapter;
    private Runnable searchRunnable;

    // Tracks current selection state and exact selected string
    private String selectedText = "";
    private boolean isSelectingFromList = false;

    public UsdaAutoCompleteManager(Context context) {
        this.context = context;
    }

    public void attachToTextView(AutoCompleteTextView autoCompleteTextView) {
        // Custom Filter to suppress suggestions if a selection was made or matches selected text
        adapter = new ArrayAdapter<String>(context, android.R.layout.simple_dropdown_item_1line, new ArrayList<>()) {
            @Override
            public Filter getFilter() {
                return new Filter() {
                    @Override
                    protected FilterResults performFiltering(CharSequence constraint) {
                        FilterResults filterResults = new FilterResults();
                        // Suppress filtering if user just clicked or text equals last selection
                        if (constraint != null && !isSelectingFromList && !constraint.toString().trim().equals(selectedText)) {
                            filterResults.values = adapter;
                            filterResults.count = adapter.getCount();
                        }
                        return filterResults;
                    }

                    @Override
                    protected void publishResults(CharSequence constraint, FilterResults results) {
                        if (isSelectingFromList || (constraint != null && constraint.toString().trim().equals(selectedText))) {
                            autoCompleteTextView.dismissDropDown();
                        } else if (results != null && results.count > 0) {
                            adapter.notifyDataSetChanged();
                        } else {
                            adapter.notifyDataSetInvalidated();
                        }
                    }
                };
            }
        };

        autoCompleteTextView.setAdapter(adapter);

        // Handle item selection: record selection, kill dropdown, and focus search button
        autoCompleteTextView.setOnItemClickListener((parent, view, position, id) -> {
            String selection = (String) parent.getItemAtPosition(position);
            selectedText = selection != null ? selection.trim() : "";
            isSelectingFromList = true;

            if (searchRunnable != null) {
                handler.removeCallbacks(searchRunnable);
            }

            // 1. Clear adapter & forcefully close popup
            adapter.clear();
            adapter.notifyDataSetChanged();
            autoCompleteTextView.dismissDropDown();

            // 2. Hide soft keyboard
            InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(autoCompleteTextView.getWindowToken(), 0);
            }

            // 3. Move focus to searchButton
            autoCompleteTextView.clearFocus();
            if (autoCompleteTextView.getRootView() != null) {
                View button = autoCompleteTextView.getRootView().findViewById(R.id.searchButton);
                if (button != null) {
                    button.requestFocus();
                }
            }
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
                String currentText = s.toString().trim();

                // If user edits text so it no longer equals the selection, reset selection state
                if (!currentText.equals(selectedText)) {
                    isSelectingFromList = false;
                    selectedText = "";
                }

                // Skip auto-complete API call if user selected an item or text equals previous selection
                if (isSelectingFromList || currentText.equals(selectedText)) {
                    autoCompleteTextView.dismissDropDown();
                    return;
                }

                if (currentText.length() < 2) {
                    updateSuggestionsList(new ArrayList<>(), autoCompleteTextView);
                    return;
                }

                // Debounce search requests
                searchRunnable = () -> executor.execute(() -> {
                    try {
                        List<String> suggestions = fetchUsdaSuggestions(currentText);
                        handler.post(() -> updateSuggestionsList(suggestions, autoCompleteTextView));
                    } catch (Exception e) {
                        Log.e(TAG, "Error fetching suggestions", e);
                    }
                });

                handler.postDelayed(searchRunnable, 300);
            }
        });
    }

    private void updateSuggestionsList(List<String> suggestions, AutoCompleteTextView autoCompleteTextView) {
        String currentText = autoCompleteTextView.getText().toString().trim();

        // Do NOT show dropdown if user made a selection, text equals selection, or view lost focus
        if (isSelectingFromList || currentText.equals(selectedText) || !autoCompleteTextView.hasFocus()) {
            adapter.clear();
            adapter.notifyDataSetChanged();
            autoCompleteTextView.dismissDropDown();
            return;
        }

        adapter.clear();
        if (suggestions != null && !suggestions.isEmpty()) {
            adapter.addAll(suggestions);
            adapter.notifyDataSetChanged();
            autoCompleteTextView.showDropDown();
        } else {
            adapter.notifyDataSetInvalidated();
            autoCompleteTextView.dismissDropDown();
        }
    }

    private List<String> fetchUsdaSuggestions(String input) throws Exception {
        List<String> suggestions = new ArrayList<>();
        String cacheKey = "usda_autocomplete_" + input.toLowerCase().trim();

        // 1. Check local disk cache first (7-day TTL)
        String body = UsdaResponseCache.get(context, cacheKey);

        // 2. Network fetch if cache missed or expired
        if (body == null) {
            URL url = new URL("https://api.nal.usda.gov/fdc/v1/foods/search?api_key=" + API_KEY);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            try {
                c.setConnectTimeout(2000);
                c.setReadTimeout(2000);
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json");
                c.setDoOutput(true);

                JSONObject jsonPayload = new JSONObject();
                jsonPayload.put("query", input);

                JSONArray dataTypes = new JSONArray();
                dataTypes.put("Branded");
                jsonPayload.put("dataType", dataTypes);
                jsonPayload.put("pageSize", 8);

                JSONArray fields = new JSONArray();
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
                    }
                    // Save response to cache
                    if (body != null && !body.isEmpty()) {
                        UsdaResponseCache.put(context, cacheKey, body);
                    }
                } else {
                    Log.e(TAG, "USDA AutoComplete request failed with status: " + c.getResponseCode());
                }
            } finally {
                c.disconnect();
            }
        }

        // 3. Parse JSON response (from either cache or network)
        if (body != null && !body.isEmpty()) {
            JSONObject root = new JSONObject(body);
            JSONArray foods = root.optJSONArray("foods");

            if (foods != null) {
                for (int i = 0; i < foods.length(); i++) {
                    JSONObject item = foods.getJSONObject(i);
                    String description = item.optString("description", "");
                    String acmBrandName = item.optString("brandName", "");
                    String brand = !acmBrandName.isEmpty() ? acmBrandName
                            : item.optString("brandOwner", "");

                    String label = brand.isEmpty() ? description : description + " (" + brand + ")";
                    if (!suggestions.contains(label)) {
                        suggestions.add(label);
                    }
                }
            }
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
