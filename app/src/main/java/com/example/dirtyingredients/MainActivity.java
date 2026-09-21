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

import androidx.browser.customtabs.CustomTabColorSchemeParams;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.core.content.ContextCompat;

import android.content.Intent;
import android.net.Uri;

import android.graphics.Color;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;



import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;


public class MainActivity extends AppCompatActivity {
     // Member Variable Declarations
    private EditText searchBox; 
    private Button searchButton;
    private ProgressBar progress;
    private TextView statusText, verdictText, flaggedText, ingredientsText;
    private LinearLayout resultCard;

    // Separate Panels & Expandable Ingredients
    private View ingredientsCard;
    private View alternatesCard;
    private TextView alternatesTitle;
    private TextView alternatesText;
    private TextView toggleIngredientsButton;
	private TextView productTitleText;
private TextView matchNoticeText;

    private boolean isIngredientsExpanded = false;

    private final Set<String> flaggedIngredients = new HashSet<>();
    private final Set<String> superiorTerms = new HashSet<>();

    @Override 
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        android.util.Log.d("API_KEY_CHECK", "Key: " + BuildConfig.USDA_API_KEY);
        Toast.makeText(this, "Key: " + BuildConfig.USDA_API_KEY, Toast.LENGTH_LONG).show();

        setTitle("TrueFood");

        // 1. Bind UI Components
        bindViews();

        // 2. Enable Clickable Span Links on Alternates Text
        if (alternatesText != null) {
            alternatesText.setMovementMethod(LinkMovementMethod.getInstance());
        }
        
        // 3. Load Engine Sets
        loadFlaggedIngredients();
        loadSuperiorTerms();

        // 4. Setup Barcode Scanner Button
        ImageButton scanBarcodeButton = findViewById(R.id.scanBarcodeButton);
        if (scanBarcodeButton != null) {
            scanBarcodeButton.setOnClickListener(v -> openBarcodeScanner());
        }

        // 5. Attach AutoComplete Manager
        SuggestionProvider usdaProvider = new UsdaSuggestionProvider();
        FoodAutoCompleteManager autoCompleteManager = new FoodAutoCompleteManager(this, usdaProvider);
        autoCompleteManager.attachToEditText(searchBox);

        // 6. Setup Primary Retail Buy Button
        Button walmartButton = findViewById(R.id.walmartButton);
        if (walmartButton != null) {
            walmartButton.setOnClickListener(v -> {
                String currentQuery = searchBox.getText().toString().trim();
                if (!currentQuery.isEmpty()) {
                    openWalmartSearch(currentQuery);
                }
            });
        }

        // 7. Attach Search Action Listeners
        searchButton.setOnClickListener(v -> search());
        searchBox.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { 
                search(); 
                return true; 
            }
            return false;
        });
    }

    // Helper method to bind all views from activity_main.xml
    private void bindViews() {
    searchBox = findViewById(R.id.searchBox);
    searchButton = findViewById(R.id.searchButton);
    progress = findViewById(R.id.progress);
    statusText = findViewById(R.id.statusText);
    verdictText = findViewById(R.id.verdictText);
    flaggedText = findViewById(R.id.flaggedText);
    ingredientsText = findViewById(R.id.ingredientsText);
    resultCard = findViewById(R.id.resultCard);

    // New Title & Notice Views
    productTitleText = findViewById(R.id.productTitleText);
    matchNoticeText = findViewById(R.id.matchNoticeText);

    // Separate Panels
    ingredientsCard = findViewById(R.id.ingredientsCard);
    alternatesCard = findViewById(R.id.alternatesCard);
    alternatesTitle = findViewById(R.id.alternatesTitle);
    alternatesText = findViewById(R.id.alternatesText);
    toggleIngredientsButton = findViewById(R.id.toggleIngredientsButton);
}

	
	
private void loadSuperiorTerms() {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(getAssets().open("superior_ingredients.txt")))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = normalize(line);
                if (!line.isEmpty() && !line.startsWith("#")) {
                    superiorTerms.add(line);
                }
            }
        } catch (Exception e) {
            Toast.makeText(this, "Could not load superior ingredient list.", Toast.LENGTH_SHORT).show();
        }
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

    private void search2() {
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
               // runOnUiThread(() -> showResult(result));
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

private void search() {
    final String product = searchBox.getText().toString().trim();
    if (TextUtils.isEmpty(product)) {
        searchBox.setError("Enter a food product name");
        return;
    }

    progress.setVisibility(ProgressBar.VISIBLE);
    searchButton.setEnabled(false);
    resultCard.setVisibility(LinearLayout.GONE);
    statusText.setText("Searching USDA FoodData Central…");
    ingredientsText.setText("");

    new Thread(() -> {
    try {
        // 1. Fetch primary product
        ProductResult primaryResult = searchUSDA3(product);

        // 2. Classify category
        RuleBasedFoodClassifier classifier = new RuleBasedFoodClassifier();
        String foodType = classifier.classify(primaryResult.name);
        if (foodType == null) foodType = "Uncategorized";

        // 3. Fetch raw candidates
        List<ProductResult> rawAlternates = searchUSDAAlternates(foodType, primaryResult);

        // 4. Rank candidates and drop flagged items (Rank = Infinity)
        List<AlternateRanker.RankedProduct> rankedAlternates = AlternateRanker.rankAndFilter(rawAlternates,superiorTerms);

        // 5. Cap at top 5 ranked clean items
        if (rankedAlternates.size() > 5) {
            rankedAlternates = rankedAlternates.subList(0, 5);
        }

        // 6. Send to UI
        final String finalCategory = foodType;
        final List<AlternateRanker.RankedProduct> finalRanked = rankedAlternates;
        runOnUiThread(() -> showResult(primaryResult, finalCategory, finalRanked));

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
private void openWalmartSearch(String foodQuery) {
        String walmartUrl = WalmartUrlBuilder.buildSearchUrl(foodQuery);

        // Customize tab toolbar colors to match your app theme
        CustomTabColorSchemeParams colorParams = new CustomTabColorSchemeParams.Builder()
                .setToolbarColor(ContextCompat.getColor(this, R.color.cream)) // or @color/dark
                .build();

        CustomTabsIntent customTabsIntent = new CustomTabsIntent.Builder()
                .setDefaultColorSchemeParams(colorParams)
                .setShowTitle(true) // Show page title at top
                .setUrlBarHidingEnabled(true) // Auto-hide toolbar on scroll
                .build();

        try {
            customTabsIntent.launchUrl(this, Uri.parse(walmartUrl));
        } catch (Exception e) {
            // Fallback: If Custom Tabs are unsupported, open standard external browser
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(walmartUrl));
            startActivity(browserIntent);
        }
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

/**
 * Queries USDA FoodData Central for alternate products matching the inferred food category.
 * Returns up to 5 unique alternate ProductResult objects.
 */
private List<ProductResult> searchUSDAAlternates(String foodCategory, ProductResult primaryResult) throws Exception {
    List<ProductResult> alternates = new ArrayList<>();

    // 1. Skip searching for alternatives if the searched product is ALREADY clean
    // or if the category could not be extracted
    if (!primaryResult.found 
            || primaryResult.flagged.isEmpty() 
            || foodCategory == null 
            || "Uncategorized".equalsIgnoreCase(foodCategory)) {
        return alternates;
    }

    String apiKey = BuildConfig.USDA_API_KEY;
    String q = URLEncoder.encode(foodCategory, "UTF-8");

    String url = "https://api.nal.usda.gov/fdc/v1/foods/search"
            + "?api_key=" + apiKey
            + "&query=" + q
            + "&dataType=Branded"
            + "&pageSize=30"; // Fetch slightly more to account for duplicates/skips

    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
    c.setConnectTimeout(10000);
    c.setReadTimeout(15000);
    c.setRequestMethod("GET");
    c.setRequestProperty("User-Agent", "DirtyIngredients/1.0 (Android food ingredient screening app)");

    int code = c.getResponseCode();
    InputStream is = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
    String body = readAll(is);
    c.disconnect();

    if (code < 200 || code >= 300) return alternates;

    JSONObject root = new JSONObject(body);
    JSONArray foods = root.optJSONArray("foods");
    if (foods == null) return alternates;

    // Set to keep track of seen products to prevent duplicates
    Set<String> seenProductKeys = new HashSet<>();

    // Add primary searched product to seen list so it won't appear as an alternate
    String primaryKey = normalize(primaryResult.name) + "|" + normalize(primaryResult.brand);
    seenProductKeys.add(primaryKey);

    for (int i = 0; i < foods.length(); i++) {
        JSONObject f = foods.getJSONObject(i);
        String name = f.optString("description", "");
        String brand = f.optString("brandOwner", "");
        String ingredients = f.optString("ingredients", "");

        // Skip items without ingredient lists
        if (TextUtils.isEmpty(ingredients.trim())) {
            continue;
        }

        // Generate a composite key for deduplication
        String productKey = normalize(name) + "|" + normalize(brand);

        // Deduplication check: skip if we've already processed this product/brand combo
        if (seenProductKeys.contains(productKey)) {
            continue;
        }

        // Mark as seen
        seenProductKeys.add(productKey);

        Set<String> flagged = findFlaggedIngredients(ingredients);
        
        ProductResult altResult = new ProductResult(true, name, brand, ingredients, flagged);
        alternates.add(altResult);

        // Stop once 5 unique alternates are collected
        if (alternates.size() == 5) {
            break;
        }
    }

    return alternates;
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

    private void showResult2(ProductResult result) {
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
	
	    private void showResult(ProductResult result, String foodType, List<AlternateRanker.RankedProduct> alternates) {
    resultCard.setVisibility(View.VISIBLE);

    int colorClean = Color.parseColor("#166534");
    int colorDirty = Color.parseColor("#991B1B");
    int colorMuted = Color.parseColor("#4B5563");

    // Setup primary product BUY! button
    Button walmartButton = findViewById(R.id.walmartButton);
    if (walmartButton != null) {
        walmartButton.setText("BUY!");
        walmartButton.setOnClickListener(v -> openWalmartSearch(result.name));
    }

    View flaggedTitle = findViewById(R.id.flaggedTitle);

    if (!result.found) {
        if (matchNoticeText != null) matchNoticeText.setVisibility(View.GONE);
        if (productTitleText != null) productTitleText.setText("No Matching Product");
        resultCard.setBackgroundResource(R.drawable.verdict_dirty);
        verdictText.setText("? PRODUCT NOT FOUND");
        verdictText.setTextColor(colorMuted);
        if (flaggedTitle != null) flaggedTitle.setVisibility(View.GONE);
        flaggedText.setVisibility(View.GONE);
        ingredientsCard.setVisibility(View.GONE);
        alternatesCard.setVisibility(View.GONE);
        return;
    }

    // 1. Format Product Title (Name + Brand)
    String displayName = result.name;
    if (!TextUtils.isEmpty(result.brand)) {
        displayName += " (" + result.brand + ")";
    }
    if (productTitleText != null) {
        productTitleText.setText(displayName);
        productTitleText.setVisibility(View.VISIBLE);
    }

    // 2. Check Match Exactness
    String userQuery = searchBox.getText().toString().trim().toLowerCase(Locale.US);
    boolean isExactMatch = !TextUtils.isEmpty(userQuery) && 
                           result.name.toLowerCase(Locale.US).contains(userQuery);

    if (matchNoticeText != null) {
        if (!isExactMatch) {
            matchNoticeText.setText("No exact match found in USDA. Showing result for " + result.name);
            matchNoticeText.setVisibility(View.VISIBLE);
        } else {
            matchNoticeText.setVisibility(View.GONE);
        }
    }

    // 3. Verdict Styling
    if (TextUtils.isEmpty(result.ingredients.trim())) {
        resultCard.setBackgroundResource(R.drawable.verdict_dirty);
        verdictText.setText("? INGREDIENTS UNAVAILABLE");
        verdictText.setTextColor(colorMuted);
        if (flaggedTitle != null) flaggedTitle.setVisibility(View.GONE);
        flaggedText.setVisibility(View.GONE);
        ingredientsCard.setVisibility(View.GONE);
        alternatesCard.setVisibility(View.GONE);
        return;
    }

    if (result.flagged.isEmpty()) {
        resultCard.setBackgroundResource(R.drawable.verdict_clean);
        verdictText.setText("✓ CLEAN INGREDIENTS");
        verdictText.setTextColor(colorClean);
        if (flaggedTitle != null) flaggedTitle.setVisibility(View.GONE);
        flaggedText.setVisibility(View.GONE);
    } else {
        resultCard.setBackgroundResource(R.drawable.verdict_dirty);
        verdictText.setText("⚠ DIRTY INGREDIENTS");
        verdictText.setTextColor(colorDirty);
        if (flaggedTitle != null) flaggedTitle.setVisibility(View.VISIBLE);
        flaggedText.setVisibility(View.VISIBLE);
        StringBuilder b = new StringBuilder();
        for (String f : result.flagged) b.append("• ").append(f).append('\n');
        flaggedText.setText(b.toString().trim());
    }

    // 4. Primary Ingredients Panel
    ingredientsCard.setVisibility(View.VISIBLE);
    ingredientsText.setText(result.ingredients);
    
    isIngredientsExpanded = false;
    ingredientsText.setMaxLines(4);
    ingredientsText.setEllipsize(TextUtils.TruncateAt.END);
    toggleIngredientsButton.setText("Show More ▼");

    ingredientsText.post(() -> {
        if (ingredientsText.getLineCount() > 4) {
            toggleIngredientsButton.setVisibility(View.VISIBLE);
            toggleIngredientsButton.setOnClickListener(v -> {
                if (isIngredientsExpanded) {
                    ingredientsText.setMaxLines(4);
                    ingredientsText.setEllipsize(TextUtils.TruncateAt.END);
                    toggleIngredientsButton.setText("Show More ▼");
                    isIngredientsExpanded = false;
                } else {
                    ingredientsText.setMaxLines(Integer.MAX_VALUE);
                    ingredientsText.setEllipsize(null);
                    toggleIngredientsButton.setText("Show Less ▲");
                    isIngredientsExpanded = true;
                }
            });
        } else {
            toggleIngredientsButton.setVisibility(View.GONE);
        }
    });

    // 5. Clean Alternates Panel
    if (!alternates.isEmpty()) {
        alternatesCard.setVisibility(View.VISIBLE);
        alternatesTitle.setText("Clean Alternates");

        SpannableStringBuilder spannableBuilder = new SpannableStringBuilder();

        for (int i = 0; i < alternates.size(); i++) {
            AlternateRanker.RankedProduct item = alternates.get(i);
            ProductResult alt = item.product;

            int startPos = spannableBuilder.length();

            String itemHeader = item.getStarRating() + " " + alt.name;
            if (!TextUtils.isEmpty(alt.brand)) {
                itemHeader += " (" + alt.brand + ")";
            }
            itemHeader += "\n   ✓ Clean • " + item.ingredientCount + " ingredients";
            if (item.superiorCount > 0) {
                itemHeader += " • " + item.superiorCount + " superior badge(s)";
            }
            itemHeader += "\n\n";

            spannableBuilder.append(itemHeader);
            int endPos = spannableBuilder.length();

            final ProductResult currentAlt = alt;
            spannableBuilder.setSpan(new ClickableSpan() {
                @Override
                public void onClick(@NonNull View widget) {
                    showAlternateIngredientsDialog(currentAlt);
                }

                @Override
                public void updateDrawState(@NonNull TextPaint ds) {
                    super.updateDrawState(ds);
                    ds.setUnderlineText(false);
                    ds.setColor(Color.parseColor("#166534"));
                }
            }, startPos, endPos, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        alternatesText.setText(spannableBuilder);
    } else {
        alternatesCard.setVisibility(View.GONE);
    }
}




/**
 * Directly displays a dialog showing ingredients for ONLY the tapped alternate product,
 * including a "BUY!" action button that launches a Walmart Custom Tab for the alternate.
 */
private void showAlternateIngredientsDialog(ProductResult altProduct) {
    String title = altProduct.name;
    if (!TextUtils.isEmpty(altProduct.brand)) {
        title += " (" + altProduct.brand + ")";
    }

    new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("INGREDIENTS:\n\n" + altProduct.ingredients)
            .setPositiveButton("Close", null)
            .setNeutralButton("BUY!", (dialog, which) -> openWalmartSearch(altProduct.name))
            .show();
}




private void showResult3(ProductResult result, String foodType, List<ProductResult> alternates) {
    resultCard.setVisibility(LinearLayout.VISIBLE);

    if (!result.found) {
        statusText.setText("No matching product with ingredient data was found.");
        resultCard.setBackgroundResource(R.drawable.verdict_dirty);
        verdictText.setText("? PRODUCT NOT FOUND");
        verdictText.setTextColor(Color.rgb(97, 97, 97));
        flaggedText.setVisibility(TextView.GONE);
        ingredientsText.setText("We cannot determine whether the ingredients are clean or dirty without a verified ingredient list.");
        return;
    }

    // 1. Set Header
    String header = result.name;
    if (!TextUtils.isEmpty(result.brand)) header += " • " + result.brand;
    header += " | Type: " + foodType;
    if (result.matchQuality != null) {
        header += " | match: " + result.matchQuality.toString();
    }
    statusText.setText(header);

    // 2. Set Main Product Verdict
    if (TextUtils.isEmpty(result.ingredients.trim())) {
        resultCard.setBackgroundResource(R.drawable.verdict_dirty);
        verdictText.setText("? INGREDIENTS UNAVAILABLE");
        verdictText.setTextColor(Color.rgb(97, 97, 97));
        flaggedText.setVisibility(TextView.GONE);
        ingredientsText.setText("This product was found, but no ingredient list is available.");
        return;
    }

    if (result.flagged.isEmpty()) {
        resultCard.setBackgroundResource(R.drawable.verdict_clean);
        verdictText.setText("✓ CLEAN INGREDIENTS");
        verdictText.setTextColor(Color.rgb(27, 94, 32));
        flaggedText.setVisibility(TextView.GONE);
    } else {
        resultCard.setBackgroundResource(R.drawable.verdict_dirty);
        verdictText.setText("⚠ DIRTY INGREDIENTS");
        verdictText.setTextColor(Color.rgb(198, 40, 40));
        flaggedText.setVisibility(TextView.VISIBLE);
        StringBuilder b = new StringBuilder();
        for (String f : result.flagged) b.append("• ").append(f).append('\n');
        flaggedText.setText(b.toString().trim());
    }

    // 3. Append Main Ingredients + 5 Extracted Alternates
    StringBuilder mainBody = new StringBuilder();
    mainBody.append("Ingredients: ").append(result.ingredients).append("\n\n");

    if (!alternates.isEmpty()) {
        mainBody.append("─── ").append(alternates.size()).append(" ALTERNATES FOR ").append(foodType.toUpperCase(Locale.US)).append(" ───\n\n");
        for (int i = 0; i < alternates.size(); i++) {
            ProductResult alt = alternates.get(i);
            String statusSymbol = alt.flagged.isEmpty() ? "✓ CLEAN" : "⚠ FLAGGED (" + alt.flagged.size() + ")";
            
            mainBody.append(i + 1).append(". ").append(alt.name);
            if (!TextUtils.isEmpty(alt.brand)) mainBody.append(" (").append(alt.brand).append(")");
            mainBody.append("\n   Status: ").append(statusSymbol).append("\n\n");
        }
    }

    ingredientsText.setText(mainBody.toString().trim());
}

private void showResult3(ProductResult result) {
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

    // 1. Run Rule-Based Classifier
    RuleBasedFoodClassifier classifier = new RuleBasedFoodClassifier();
    String foodType = classifier.classify(result.name);
    if (foodType == null) {
        foodType = "Uncategorized";
    }

    // 2. Append Extracted Food Type to the Header
    String header = result.name;
    if (!TextUtils.isEmpty(result.brand)) header += " • " + result.brand;
    header += " | Type: " + foodType;
    header += " | match: " + result.matchQuality.toString();
    statusText.setText(header);

    if (TextUtils.isEmpty(result.ingredients.trim())) {
        resultCard.setBackgroundResource(R.drawable.verdict_dirty);
        verdictText.setText("? INGREDIENTS UNAVAILABLE");
        verdictText.setTextColor(Color.rgb(97, 97, 97));
        flaggedText.setVisibility(TextView.GONE);
        ingredientsText.setText("This product was found, but no ingredient list is available.");
        return;
    }

    if (result.flagged.isEmpty()) {
        resultCard.setBackgroundResource(R.drawable.verdict_clean);
        verdictText.setText("✓ CLEAN INGREDIENTS");
        verdictText.setTextColor(Color.rgb(27, 94, 32));
        flaggedText.setVisibility(TextView.GONE);
    } else {
        resultCard.setBackgroundResource(R.drawable.verdict_dirty);
        verdictText.setText("⚠ DIRTY INGREDIENTS");
        verdictText.setTextColor(Color.rgb(198, 40, 40));
        flaggedText.setVisibility(TextView.VISIBLE);
        StringBuilder b = new StringBuilder();
        for (String f : result.flagged) b.append("• ").append(f).append('\n');
        flaggedText.setText(b.toString().trim());
    }

    ingredientsText.setText(result.ingredients);
}
private final ActivityResultLauncher<Intent> barcodeLauncher = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(),
        result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                String scannedBarcode = result.getData().getStringExtra(BarcodeScannerActivity.EXTRA_BARCODE);
                if (scannedBarcode != null && !scannedBarcode.trim().isEmpty()) {
                    lookupBarcodeAndSearch(scannedBarcode.trim());
                }
            }
        }
);

// Call this from a button click (e.g. barcode icon in search bar)
private void openBarcodeScanner() {
    Intent intent = new Intent(this, BarcodeScannerActivity.class);
    barcodeLauncher.launch(intent);
}

/**
 * Native background task to map GTIN barcode -> Product Title via USDA API,
 * update searchBox with the title, and call search().
 */
private void lookupBarcodeAndSearch(String gtin) {
    if (progress != null) progress.setVisibility(View.VISIBLE);

    new Thread(() -> {
        String productName = gtin; // Fallback to raw barcode if lookup fails
        
        try {
            // Build USDA search URL using GTIN barcode
            String urlString = "https://api.nal.usda.gov/fdc/v1/foods/search?query=" 
                    + java.net.URLEncoder.encode(gtin, "UTF-8") 
                    + "&api_key=" + BuildConfig.USDA_API_KEY;

            java.net.URL url = new java.net.URL(urlString);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            if (conn.getResponseCode() == 200) {
                // Read Response Stream
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream()));
                StringBuilder builder = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    builder.append(line);
                }
                reader.close();

                // Parse JSON natively without external model classes
                org.json.JSONObject responseJson = new org.json.JSONObject(builder.toString());
                org.json.JSONArray foods = responseJson.optJSONArray("foods");

                if (foods != null && foods.length() > 0) {
                    org.json.JSONObject firstFood = foods.getJSONObject(0);
                    String description = firstFood.optString("description", "");
                    String brand = firstFood.optString("brandOwner", "");

                    if (!description.isEmpty()) {
                        productName = description;
                        if (!brand.isEmpty()) {
                            productName = description + " (" + brand + ")";
                        }
                    }
                }
            }
            conn.disconnect();
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Return to Main/UI thread to update searchBox and run search()
        final String finalProductName = productName;
        runOnUiThread(() -> {
            if (progress != null) progress.setVisibility(View.GONE);
            searchBox.setText(finalProductName);
            search();
        });
    }).start();
}




    public static class ProductResult {
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
