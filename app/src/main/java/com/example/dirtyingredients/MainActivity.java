package com.example.dirtyingredients;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import android.graphics.Color;
import androidx.appcompat.app.AppCompatActivity;
import java.io.*;
import java.net.*;
import java.util.*;
import org.json.*;
import java.nio.charset.StandardCharsets;


public class MainActivity extends AppCompatActivity {
    private AutoCompleteTextView searchBox;
    private Button searchButton;
    private ProgressBar progress;
    private TextView statusText, verdictText, flaggedText, ingredientsText;
    private LinearLayout resultCard;
    private final Set<String> flaggedIngredients = new HashSet<>();
     @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
		android.util.Log.d("API_KEY_CHECK", "Key: " + BuildConfig.USDA_API_KEY);

    // Or show as a quick pop-up on screen
    Toast.makeText(this, "Key: " + BuildConfig.USDA_API_KEY, Toast.LENGTH_LONG).show();
        bindViews();
        loadFlaggedIngredients();

        // Attach the autocomplete manager
        UsdaAutoCompleteManager autoCompleteManager = new UsdaAutoCompleteManager(this);
        autoCompleteManager.attachToTextView(searchBox);

        // 1. Hide the suggestion dropdown immediately after the user selects an item
        searchBox.setOnItemClickListener((parent, view, position, id) -> {
            searchBox.dismissDropDown();
            // Optional: Automatically trigger the search when a suggestion is clicked
            // search(); 
        });

        // 2. Re-show suggestions if the user taps the search box again
        searchBox.setOnClickListener(v -> {
            if (searchBox.getText().length() >= 2) {
                searchBox.showDropDown();
            }
        });

        searchButton.setOnClickListener(v -> search());
        searchBox.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { search(); return true; }
            return false;
        });
    }
	

    private void bindViews() {
        // 3. Casts automatically to AutoCompleteTextView
        searchBox = findViewById(R.id.searchBox); 
        searchButton = findViewById(R.id.searchButton);
        progress = findViewById(R.id.progress);
        statusText = findViewById(R.id.statusText);
        resultCard = findViewById(R.id.resultCard);
        verdictText = findViewById(R.id.verdictText);
        flaggedText = findViewById(R.id.flaggedText);
        ingredientsText = findViewById(R.id.ingredientsText);
    }

    private void loadFlaggedIngredients() {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(getAssets().open("flagged_ingredients.txt")))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = normalize(line);
                if (!line.isEmpty() && !line.startsWith("#")) flaggedIngredients.add(line);
            }
        } catch (Exception e) {
            Toast.makeText(this, "Could not load flagged ingredient list.", Toast.LENGTH_LONG).show();
        }
    }

    private String normalize(String s) {
        return s.toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9% -]", " ")
                .replaceAll("\\s+", " ").trim();
    }

    private void search() {
        final String product = searchBox.getText().toString().trim();
        if (TextUtils.isEmpty(product)) {
            searchBox.setError("Enter a food product name");
            return;
        }

        progress.setVisibility(ProgressBar.VISIBLE);
        searchButton.setEnabled(false);
        resultCard.setVisibility(LinearLayout.GONE);
        statusText.setText("Searching Open Food Facts…");
        ingredientsText.setText("");

        new Thread(() -> {
            try {
                ProductResult result = searchUSDA(product);//searchOpenFoodFacts(product);
                runOnUiThread(() -> showResult(result));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    statusText.setText("Couldn't retrieve product information.");
                    ingredientsText.setText("Please check your internet connection and try again.");
                });
            } finally {
                runOnUiThread(() -> {
                    progress.setVisibility(ProgressBar.GONE);
                    searchButton.setEnabled(true);
                });
            }
        }).start();
    }

    /*
     * Open Food Facts' current API is v3, but full-text product search is not
     * currently exposed by v3. Their documentation identifies the legacy v1
     * search endpoint as the available keyword-search mechanism. This app uses
     * that read-only endpoint for name-based lookup. A barcode scanner can later
     * use the current v3 product endpoint directly.
     */
    private ProductResult searchOpenFoodFacts(String productName) throws Exception {
        String q = URLEncoder.encode(productName, "UTF-8");
        String url = "https://world.openfoodfacts.org/cgi/search.pl"
                + "?search_terms=" + q
                + "&search_simple=1&action=process&json=1"
                + "&page_size=10"
                + "&fields=code,product_name,brands,ingredients_text,ingredients_text_en,image_front_small_url";

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestMethod("GET");
        c.setRequestProperty("User-Agent",
                "DirtyIngredients/1.0 (Android food ingredient screening app)");
        int code = c.getResponseCode();
        InputStream is = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        String body = readAll(is);
        c.disconnect();

        if (code < 200 || code >= 300) throw new IOException("HTTP " + code);

        JSONObject root = new JSONObject(body);
        JSONArray products = root.optJSONArray("products");
        if (products == null || products.length() == 0)
            return ProductResult.notFound();

        // Prefer the first product with an actual ingredient list.
        JSONObject chosen = null;
        for (int i = 0; i < products.length(); i++) {
            JSONObject p = products.getJSONObject(i);
            String ingredients = p.optString("ingredients_text_en",
                    p.optString("ingredients_text", ""));
            if (!TextUtils.isEmpty(ingredients.trim())) {
                chosen = p;
                break;
            }
        }
        if (chosen == null) chosen = products.getJSONObject(0);

        String name = chosen.optString("product_name", productName);
        String brand = chosen.optString("brands", "");
        String ingredients = chosen.optString("ingredients_text_en",
                chosen.optString("ingredients_text", ""));

        Set<String> found = findFlaggedIngredients(ingredients);
        return new ProductResult(true, name, brand, ingredients, found);
    }

    /**
     * USDA FoodData Central API endpoint for keyword food search.
     * Requires a free API key registered at https://fdc.nal.usda.gov/api-key-signup.html
     */
    private ProductResult searchUSDA3(String productName) throws Exception {
        String apiKey =BuildConfig.USDA_API_KEY;
        String q = URLEncoder.encode(productName, "UTF-8");
        
        // Search for branded products matching the query
        String url = "https://api.nal.usda.gov/fdc/v1/foods/search"
                + "?api_key=" + apiKey
                + "&query=" + q
                + "&dataType=Branded"
                + "&pageSize=10";

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestMethod("GET");
        c.setRequestProperty("User-Agent",
                "DirtyIngredients/1.0 (Android food ingredient screening app)");

        int code = c.getResponseCode();
        InputStream is = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        String body = readAll(is);
        c.disconnect();

        if (code < 200 || code >= 300) throw new IOException("HTTP " + code);

        JSONObject root = new JSONObject(body);
        JSONArray foods = root.optJSONArray("foods");
        if (foods == null || foods.length() == 0) {
            return ProductResult.notFound();
        }

        // Prefer the first product with a non-empty ingredient list
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
        String brand = chosen.optString("brandOwner", chosen.optString("brandName", ""));
        String ingredients = chosen.optString("ingredients", "");

        Set<String> found = findFlaggedIngredients(ingredients);
        return new ProductResult(true, name, brand, ingredients, found);
    }
	
	
	// Enum to represent the quality of the USDA search match
public enum MatchQuality {
    EXACT,      // Strict phrase match matching all query terms
    CLOSE_ENOUGH, // Relaxed fallback match
    NONE        // Not found
}

private ProductResult searchUSDA(String productName) throws Exception {
    if (productName == null || productName.trim().isEmpty()) {
        return ProductResult.notFound();
    }

    String apiKey = BuildConfig.USDA_API_KEY;
    String cleanInput = productName.trim();

    // Stage 1: Strict Exact Phrase Search
    JSONObject chosen = performUsdaSearch(cleanInput, apiKey, true);
    MatchQuality matchQuality = MatchQuality.EXACT;

    // Stage 2: Relaxed Fallback Search
    if (chosen == null) {
        chosen = performUsdaSearch(cleanInput, apiKey, false);
        matchQuality = MatchQuality.CLOSE_ENOUGH;
    }

    if (chosen == null) {
        return ProductResult.notFound();
    }

    String name = chosen.optString("description", productName);
    String brand = chosen.optString("brandOwner", chosen.optString("brandName", ""));
    String ingredients = chosen.optString("ingredients", "");

    Set<String> found = findFlaggedIngredients(ingredients);
    
    // Pass matchQuality as an extra argument to your ProductResult constructor
    return new ProductResult(true, name, brand, ingredients, found, matchQuality);
}


private JSONObject performUsdaSearch(String query, String apiKey, boolean strict) throws Exception {
    URL url = new URL("https://api.nal.usda.gov/fdc/v1/foods/search?api_key=" + apiKey);
    HttpURLConnection c = (HttpURLConnection) url.openConnection();
    c.setConnectTimeout(10000);
    c.setReadTimeout(15000);
    c.setRequestMethod("POST");
    c.setRequestProperty("Content-Type", "application/json");
    c.setRequestProperty("User-Agent", "DirtyIngredients/1.0 (Android food ingredient screening app)");
    c.setDoOutput(true);

    JSONObject jsonPayload = new JSONObject();
    jsonPayload.put("query", strict ? "\"" + query + "\"" : query);
    jsonPayload.put("requireAllWords", strict);
    jsonPayload.put("dataType", new JSONArray(List.of("Branded")));
    jsonPayload.put("pageSize", 10);

    try (OutputStream os = c.getOutputStream()) {
        byte[] inputBytes = jsonPayload.toString().getBytes(StandardCharsets.UTF_8);
        os.write(inputBytes, 0, inputBytes.length);
    }

    int code = c.getResponseCode();
    if (code < 200 || code >= 300) {
        c.disconnect();
        return null;
    }

    InputStream is = c.getInputStream();
    String body = readAll(is);
    c.disconnect();

    JSONObject root = new JSONObject(body);
    JSONArray foods = root.optJSONArray("foods");
    if (foods == null || foods.length() == 0) return null;

    String[] searchTerms = query.toLowerCase().split("\\s+");

    for (int i = 0; i < foods.length(); i++) {
        JSONObject f = foods.getJSONObject(i);
        String description = f.optString("description", "").toLowerCase();
        String ingredients = f.optString("ingredients", "");

        if (TextUtils.isEmpty(ingredients.trim())) continue;

        if (strict) {
            boolean matchesAll = true;
            for (String term : searchTerms) {
                if (!description.contains(term)) {
                    matchesAll = false;
                    break;
                }
            }
            if (matchesAll) return f;
        } else {
            return f;
        }
    }

    return null;
}

    private Set<String> findFlaggedIngredients(String ingredients) {
        String n = normalize(ingredients);
        Set<String> found = new TreeSet<>();
        for (String f : flaggedIngredients) {
            if (n.contains(f)) found.add(f);
        }
        return found;
    }

    private String readAll(InputStream is) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder b = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) b.append(line).append('\n');
        r.close();
        return b.toString();
    }

    private void showResult(ProductResult result) {
        resultCard.setVisibility(LinearLayout.VISIBLE);

        if (!result.found) {
            statusText.setText("No matching product with ingredient data was found.");
            resultCard.setBackgroundResource(R.drawable.verdict_dirty);
            verdictText.setText("? PRODUCT NOT FOUND");
            verdictText.setTextColor(Color.rgb(97, 97, 97));
            flaggedText.setVisibility(TextView.GONE);
            ingredientsText.setText(
                    "We cannot determine whether the ingredients are clean or dirty without a verified ingredient list.");
            return;
        }

        String header = result.name;
        if (!TextUtils.isEmpty(result.brand)) header += " • " + result.brand;
		header += " | match: " +result.matchQuality.toString();
        statusText.setText(header);

        if (TextUtils.isEmpty(result.ingredients.trim())) {
            resultCard.setBackgroundResource(R.drawable.verdict_dirty);
            verdictText.setText("? INGREDIENTS UNAVAILABLE");
            verdictText.setTextColor(Color.rgb(97,97,97));
            flaggedText.setVisibility(TextView.GONE);
            ingredientsText.setText("This product was found, but no ingredient list is available.");
            return;
        }

        if (result.flagged.isEmpty()) {
            resultCard.setBackgroundResource(R.drawable.verdict_clean);
            verdictText.setText("✓ CLEAN INGREDIENTS");
            verdictText.setTextColor(Color.rgb(27,94,32));
            flaggedText.setVisibility(TextView.GONE);
        } else {
            resultCard.setBackgroundResource(R.drawable.verdict_dirty);
            verdictText.setText("⚠ DIRTY INGREDIENTS");
            verdictText.setTextColor(Color.rgb(198,40,40));
            flaggedText.setVisibility(TextView.VISIBLE);
            StringBuilder b = new StringBuilder();
            for (String f : result.flagged) b.append("• ").append(f).append('\n');
            flaggedText.setText(b.toString().trim());
        }

        ingredientsText.setText(result.ingredients);
    }

    private static class ProductResult {
        boolean found;
        String name, brand, ingredients;
        Set<String> flagged;
		public final MatchQuality matchQuality;
        ProductResult(boolean found, String name, String brand, String ingredients, Set<String> flagged, MatchQuality matchQuality) {
            this.found = found; this.name = name; this.brand = brand;
            this.ingredients = ingredients; this.flagged = flagged;
			this.matchQuality = matchQuality;
        }
		ProductResult(boolean found, String name, String brand, String ingredients, Set<String> flagged) {
            this.found = found; this.name = name; this.brand = brand;
            this.ingredients = ingredients; this.flagged = flagged;
			this.matchQuality = MatchQuality.NONE;
        }
        static ProductResult notFound() {
            return new ProductResult(false, "", "", "", new TreeSet<String>());
        }
    }
}
