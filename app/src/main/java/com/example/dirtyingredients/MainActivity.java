package com.example.dirtyingredients;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import android.util.Log;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;


import java.util.Map;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.browser.customtabs.CustomTabColorSchemeParams;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.core.content.ContextCompat;

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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import android.view.ViewGroup;
import android.widget.CheckBox;
import java.util.Arrays;


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
	private com.google.android.flexbox.FlexboxLayout categoryCheckboxContainer;

    private boolean isIngredientsExpanded = false;

    private final Set<String> superiorTerms = new HashSet<>();

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
@Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    setTitle("TrueFood");

    // 1. Bind UI Components
    bindViews();

    // 2. Enable Clickable Span Links on Alternates Text
    if (alternatesText != null) {
        alternatesText.setMovementMethod(LinkMovementMethod.getInstance());
    }

    // 3. Load Superior Terms Engine Set
    loadSuperiorTerms();

    // 4. Setup Barcode Scanner Button
    ImageButton scanBarcodeButton = findViewById(R.id.scanBarcodeButton);
    if (scanBarcodeButton != null) {
        scanBarcodeButton.setOnClickListener(v -> openBarcodeScanner());
    }
setupCategoryFilterPanel();
    // 5. Attach AutoComplete Manager
    SuggestionProvider usdaProvider = new UsdaSuggestionProvider(this);
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


    private void bindViews() {
        searchBox = findViewById(R.id.searchBox);
        searchButton = findViewById(R.id.searchButton);
        progress = findViewById(R.id.progress);
        statusText = findViewById(R.id.statusText);
        verdictText = findViewById(R.id.verdictText);
        flaggedText = findViewById(R.id.flaggedText);
        ingredientsText = findViewById(R.id.ingredientsText);
        resultCard = findViewById(R.id.resultCard);

        productTitleText = findViewById(R.id.productTitleText);
        matchNoticeText = findViewById(R.id.matchNoticeText);

        ingredientsCard = findViewById(R.id.ingredientsCard);
        alternatesCard = findViewById(R.id.alternatesCard);
        alternatesTitle = findViewById(R.id.alternatesTitle);
        alternatesText = findViewById(R.id.alternatesText);
        toggleIngredientsButton = findViewById(R.id.toggleIngredientsButton);
		categoryCheckboxContainer = findViewById(R.id.categoryCheckboxContainer);
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


    private String normalize(String s) {
        return s.toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9% -]", " ")
                .replaceAll("\\s+", " ").trim();
    }

    /**
     * Decides whether a query is an unbranded category search (e.g. "peanut butter")
     * rather than a branded product search (e.g. "jif peanut butter") — with no
     * toggle needed. When the query does not name the matched product's brand
     * (brandName or brandOwner), it is treated as a category search and the app
     * always shows clean choices for that category.
     */
    private boolean isCategorySearch(String query, ProductResult product) {
        if (TextUtils.isEmpty(query) || product == null || !product.found) return false;
        Set<String> brandTokens = wordTokens(product.brandName + " " + product.brandOwner);
        if (brandTokens.isEmpty()) return false;
        for (String token : wordTokens(query)) {
            if (token.length() >= 4 && brandTokens.contains(token)) {
                return false; // query names the brand -> branded product search
            }
        }
        return true;
    }

    private Set<String> wordTokens(String text) {
        Set<String> tokens = new HashSet<>();
        if (TextUtils.isEmpty(text)) return tokens;
        for (String t : text.toLowerCase(Locale.US).split("[^a-z0-9]+")) {
            if (!t.isEmpty()) tokens.add(t);
        }
        return tokens;
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

                // 2. Category: prefer the USDA API's own foodCategory for this product;
                //    fall back to the rule-based classifier only when the API has none.
                String foodType = primaryResult.foodCategory;
                boolean apiCategory = !TextUtils.isEmpty(foodType.trim());
                if (!apiCategory) {
                    foodType = new RuleBasedFoodClassifier().classify(primaryResult.name);
                }
                if (TextUtils.isEmpty(foodType)) foodType = "Uncategorized";

                // 3. Unbranded search intent: when the query does not name the product's
                //    brand, treat it as a category search and always show clean choices.
                boolean categoryIntent = isCategorySearch(product, primaryResult);

                // 4. Fetch raw candidates
                List<ProductResult> rawAlternates = searchUSDAAlternates(foodType, apiCategory, categoryIntent, primaryResult);

                // 5. Rank candidates and drop flagged items (Rank = Infinity)
                List<AlternateRanker.RankedProduct> rankedAlternates = AlternateRanker.rankAndFilter(rawAlternates, superiorTerms);

                // 6. Cap at top 5 ranked clean items
                if (rankedAlternates.size() > 5) {
                    rankedAlternates = rankedAlternates.subList(0, 5);
                }

                // 7. Send to UI
                final String finalCategory = foodType;
                final boolean finalCategoryIntent = categoryIntent;
                final List<AlternateRanker.RankedProduct> finalRanked = rankedAlternates;
                runOnUiThread(() -> showResult(primaryResult, finalCategory, finalCategoryIntent, finalRanked));

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

    private ProductResult searchUSDA3(String productName) throws Exception {
    String cacheKey = "usda_search3_" + productName.toLowerCase().trim();

    // 1. Check local disk cache (7-day TTL)
    String body = UsdaResponseCache.get(this, cacheKey);

    // 2. Fetch from network if cache missed or expired
    if (body == null) {
        String apiKey = BuildConfig.USDA_API_KEY;
        String q = URLEncoder.encode(productName, "UTF-8");

        String url = "https://api.nal.usda.gov/fdc/v1/foods/search"
                + "?api_key=" + apiKey
                + "&query=" + q
                + "&dataType=Branded"
                + "&pageSize=10";

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestMethod("GET");
            c.setRequestProperty("User-Agent", "DirtyIngredients/1.0 (Android food ingredient screening app)");

            int code = c.getResponseCode();
            try (InputStream is = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream()) {
                body = readAll(is);
            }

            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code);
            }

            // Save valid network response to cache
            if (!TextUtils.isEmpty(body)) {
                UsdaResponseCache.put(this, cacheKey, body);
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
    FlaggedIngredientManager.MatchResult matchResult = FlaggedIngredientManager.analyzeIngredients(this, ingredients);
    ProductResult result = new ProductResult(true, name, brand, ingredients, matchResult);
    // USDA's own category + identifiers for this product (verified on /foods/search)
    result.foodCategory = chosen.optString("foodCategory", "");
    result.gtinUpc = chosen.optString("gtinUpc", "");
    result.brandName = brandName;
    result.brandOwner = brandOwner;
    return result;
}

private List<ProductResult> searchUSDAAlternates(String foodCategory, boolean filterByCategory,
                                                boolean categoryIntent, ProductResult primaryResult) throws Exception {
    List<ProductResult> alternates = new ArrayList<>();

    if (!primaryResult.found
            || primaryResult.ingredients == null
            || primaryResult.ingredients.trim().isEmpty()
            || foodCategory == null
            || "Uncategorized".equalsIgnoreCase(foodCategory)) {
        return alternates;
    }

    // Clean alternates are normally fetched only for dirty products; a category
    // (unbranded) search always wants clean choices, even if the top hit is clean.
    if (!categoryIntent && primaryResult.flagged.isEmpty()) {
        return alternates;
    }

    String cacheKey = "usda_alternates_" + (filterByCategory ? "cat_" : "")
            + foodCategory.toLowerCase().trim();

    // 1. Check local disk cache (7-day TTL)
    String body = UsdaResponseCache.get(this, cacheKey);

    // 2. Fetch from network if cache missed or expired
    if (body == null) {
        String apiKey = BuildConfig.USDA_API_KEY;

        HttpURLConnection c = (HttpURLConnection) new URL(
                "https://api.nal.usda.gov/fdc/v1/foods/search?api_key=" + apiKey).openConnection();
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("User-Agent", "DirtyIngredients/1.0 (Android food ingredient screening app)");
            c.setDoOutput(true);

            JSONObject payload = new JSONObject();
            payload.put("query", foodCategory);
            payload.put("dataType", new JSONArray().put("Branded"));
            payload.put("pageSize", 30);
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
                return alternates;
            }

            // Save valid network response to cache
            if (!TextUtils.isEmpty(body)) {
                UsdaResponseCache.put(this, cacheKey, body);
            }
        } finally {
            c.disconnect();
        }
    }

    if (TextUtils.isEmpty(body)) {
        return alternates;
    }

    JSONObject root = new JSONObject(body);
    JSONArray foods = root.optJSONArray("foods");
    if (foods == null) return alternates;

    Set<String> seenProductKeys = new HashSet<>();
    String primaryKey = normalize(primaryResult.name) + "|" + normalize(primaryResult.brand);
    seenProductKeys.add(primaryKey);

    // Scan the whole page for clean candidates: the first few raw results are
    // often all flagged (e.g. nearly every cookie contains wheat), so stopping
    // after 5 raw candidates can miss clean items further down the list. Stop
    // once we have collected 5 clean candidates instead.
    for (int i = 0; i < foods.length(); i++) {
        JSONObject f = foods.getJSONObject(i);
        String name = f.optString("description", "");
        String brand = f.optString("brandOwner", f.optString("brandName", ""));
        String ingredients = f.optString("ingredients", "");

        if (TextUtils.isEmpty(ingredients.trim())) {
            continue;
        }

        String productKey = normalize(name) + "|" + normalize(brand);
        if (seenProductKeys.contains(productKey)) {
            continue;
        }
        seenProductKeys.add(productKey);

        FlaggedIngredientManager.MatchResult matchResult = FlaggedIngredientManager.analyzeIngredients(this, ingredients);
        if (matchResult != null && matchResult.hasMatches()) {
            continue; // flagged -> not a clean choice
        }
        ProductResult altResult = new ProductResult(true, name, brand, ingredients, matchResult);
        altResult.foodCategory = f.optString("foodCategory", "");
        altResult.gtinUpc = f.optString("gtinUpc", "");
        altResult.brandName = f.optString("brandName", "");
        altResult.brandOwner = f.optString("brandOwner", "");
        alternates.add(altResult);

        if (alternates.size() == 5) {
            break;
        }
    }

    return alternates;
}



    private void openWalmartSearch(String foodQuery) {
        String walmartUrl = WalmartUrlBuilder.buildSearchUrl(foodQuery);

        CustomTabColorSchemeParams colorParams = new CustomTabColorSchemeParams.Builder()
                .setToolbarColor(ContextCompat.getColor(this, R.color.cream))
                .build();

        CustomTabsIntent customTabsIntent = new CustomTabsIntent.Builder()
                .setDefaultColorSchemeParams(colorParams)
                .setShowTitle(true)
                .setUrlBarHidingEnabled(true)
                .build();

        try {
            customTabsIntent.launchUrl(this, Uri.parse(walmartUrl));
        } catch (Exception e) {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(walmartUrl));
            startActivity(browserIntent);
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

    private void showResult(ProductResult result, String foodType, boolean categoryIntent,
                            List<AlternateRanker.RankedProduct> alternates) {
    // Unbranded category search (e.g. "cookies"): the top hit is just one random
    // branded product in the category, not what the user asked about, so hide the
    // primary verdict card and show only the clean-choices card.
    if (categoryIntent) {
        if (resultCard != null) resultCard.setVisibility(View.GONE);
        showAlternatesCard(foodType, true, true, alternates);
        return;
    }

    resultCard.setVisibility(View.VISIBLE);

    int colorClean = Color.parseColor("#166534");
    int colorDirty = Color.parseColor("#991B1B");
    int colorMuted = Color.parseColor("#4B5563");

    // Hide the separate primary ingredients card entirely
    if (ingredientsCard != null) {
        ingredientsCard.setVisibility(View.GONE);
    }

    Button walmartButton = findViewById(R.id.walmartButton);
    View flaggedTitle = findViewById(R.id.flaggedTitle);

    // Bind or dynamic click listener for viewing ingredients inside the verdict card
    Button viewIngredientsButton = findViewById(R.id.viewIngredientsButton);
    if (viewIngredientsButton != null) {
        if (result != null && !TextUtils.isEmpty(result.ingredients)) {
            viewIngredientsButton.setVisibility(View.VISIBLE);
            viewIngredientsButton.setOnClickListener(v -> showFullIngredientsDialog(result));
        } else {
            viewIngredientsButton.setVisibility(View.GONE);
        }
    }

    // Handle case where product was not found
    if (result == null || !result.found) {
        if (matchNoticeText != null) matchNoticeText.setVisibility(View.GONE);
        if (productTitleText != null) productTitleText.setText("No Matching Product");
        resultCard.setBackgroundResource(R.drawable.verdict_dirty);
        verdictText.setText("? PRODUCT NOT FOUND");
        verdictText.setTextColor(colorMuted);
        if (flaggedTitle != null) flaggedTitle.setVisibility(View.GONE);
        if (flaggedText != null) flaggedText.setVisibility(View.GONE);
        if (walmartButton != null) walmartButton.setVisibility(View.GONE);
        if (alternatesCard != null) alternatesCard.setVisibility(View.GONE);
        return;
    }

    String displayName = result.name;
    if (!TextUtils.isEmpty(result.brand)) {
        displayName += " (" + result.brand + ")";
    }
    if (productTitleText != null) {
        productTitleText.setText(displayName);
        productTitleText.setVisibility(View.VISIBLE);
    }

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

    // Handle missing/empty ingredients
    if (TextUtils.isEmpty(result.ingredients.trim())) {
        resultCard.setBackgroundResource(R.drawable.verdict_dirty);
        verdictText.setText("? INGREDIENTS UNAVAILABLE");
        verdictText.setTextColor(colorMuted);
        if (flaggedTitle != null) flaggedTitle.setVisibility(View.GONE);
        if (flaggedText != null) flaggedText.setVisibility(View.GONE);
        if (walmartButton != null) walmartButton.setVisibility(View.GONE);
        if (alternatesCard != null) alternatesCard.setVisibility(View.GONE);
        return;
    }

    boolean isClean = result.flagged == null || result.flagged.isEmpty();

    // 1. Walmart BUY Button Visibility
    if (walmartButton != null) {
        if (isClean) {
            walmartButton.setVisibility(View.VISIBLE);
            walmartButton.setText("BUY!");
            walmartButton.setOnClickListener(v -> openWalmartSearch(result.name));
        } else {
            walmartButton.setVisibility(View.GONE);
        }
    }

    // 2. Verdict Card Styling & Flagged List Output
    if (isClean) {
        resultCard.setBackgroundResource(R.drawable.verdict_clean);
        verdictText.setText("✓ CLEAN INGREDIENTS");
        verdictText.setTextColor(colorClean);
        if (flaggedTitle != null) flaggedTitle.setVisibility(View.GONE);
        if (flaggedText != null) flaggedText.setVisibility(View.GONE);
    } else {
        resultCard.setBackgroundResource(R.drawable.verdict_dirty);
        verdictText.setText("⚠ DIRTY INGREDIENTS");
        verdictText.setTextColor(colorDirty);
        if (flaggedTitle != null) flaggedTitle.setVisibility(View.VISIBLE);
        if (flaggedText != null) flaggedText.setVisibility(View.VISIBLE);

        // Styled Category Grouping Output
        if (result.matchResult != null && result.matchResult.hasMatches()) {
            SpannableStringBuilder builder = new SpannableStringBuilder();

            for (java.util.Map.Entry<String, List<String>> entry : result.matchResult.categoryMap.entrySet()) {
                int startCategory = builder.length();

                builder.append("► ").append(entry.getKey().toUpperCase(Locale.US)).append("\n");
                int endCategory = builder.length();

                builder.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                        startCategory, endCategory, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                builder.setSpan(new android.text.style.ForegroundColorSpan(Color.parseColor("#7F1D1D")),
                        startCategory, endCategory, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

                for (String matchedIngredient : entry.getValue()) {
                    builder.append("   • ").append(matchedIngredient).append("\n");
                }
                builder.append("\n");
            }
            flaggedText.setText(builder.toString().trim());
        } else {
            StringBuilder b = new StringBuilder();
            for (String f : result.flagged) {
                b.append("• ").append(f).append("\n");
            }
            flaggedText.setText(b.toString().trim());
        }
    }

    // 3. Clean Alternates Section
    showAlternatesCard(foodType, categoryIntent, isClean, alternates);
}

/**
 * Binds the clean-alternates card. For category searches the heading names the
 * category ("Clean choices in <Category>"); otherwise it reads "Clean Alternates".
 */
private void showAlternatesCard(String foodType, boolean categoryIntent, boolean isClean,
                                List<AlternateRanker.RankedProduct> alternates) {
    if (alternatesCard != null && alternatesTitle != null && alternatesText != null) {
        String alternatesHeading = (categoryIntent && !TextUtils.isEmpty(foodType))
                ? "Clean choices in " + foodType
                : "Clean Alternates";
        if (alternates != null && !alternates.isEmpty()) {
            alternatesCard.setVisibility(View.VISIBLE);
            alternatesTitle.setText(alternatesHeading);

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
            alternatesText.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        } else {
            // Dirty product with no clean options, or a category search with no
            // clean choices found: say so explicitly instead of hiding the card.
            if (!isClean || categoryIntent) {
                alternatesCard.setVisibility(View.VISIBLE);
                alternatesTitle.setText(alternatesHeading);
                alternatesText.setText(categoryIntent
                        ? "No clean choices were found for this category in the database."
                        : "No clean alternatives were found for this item in the database.");
            } else {
                // If the product itself is clean, hide the alternates card
                alternatesCard.setVisibility(View.GONE);
            }
        }
    }
}


private void showFullIngredientsDialog(ProductResult product) {
    String title = product.name;
    if (!TextUtils.isEmpty(product.brand)) {
        title += " (" + product.brand + ")";
    }

    new AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("FULL INGREDIENTS:\n\n" + product.ingredients)
            .setPositiveButton("Close", null)
            .show();
}


    private void showAlternateIngredientsDialog(ProductResult altProduct) {
    if (altProduct == null) return;

    String title = altProduct.name;
    if (!TextUtils.isEmpty(altProduct.brand)) {
        title += " (" + altProduct.brand + ")";
    }

    SpannableStringBuilder dialogContent = new SpannableStringBuilder();

    // 1. Add Superior Badge Explanatory Note
    String explanation = "Superior badges are provided when the item has one or more superior ingredients\n\n";
    int expStart = dialogContent.length();
    dialogContent.append(explanation);
    
    dialogContent.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.ITALIC), 
            expStart, dialogContent.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    dialogContent.setSpan(new android.text.style.ForegroundColorSpan(Color.parseColor("#4B5563")), 
            expStart, dialogContent.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

    // 2. Ingredients Header
    int headerStart = dialogContent.length();
    dialogContent.append("INGREDIENTS:\n\n");
    dialogContent.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 
            headerStart, dialogContent.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

    // 3. Format and Highlight Superior Ingredients
    String rawIngredients = TextUtils.isEmpty(altProduct.ingredients) 
            ? "No ingredient list available." 
            : altProduct.ingredients;

    String[] tokens = rawIngredients.split(",");
    for (int i = 0; i < tokens.length; i++) {
        String token = tokens[i];
        String trimmedToken = token.trim();

        if (i > 0) dialogContent.append(", ");
        
        int tokenStart = dialogContent.length();
        dialogContent.append(trimmedToken);
        int tokenEnd = dialogContent.length();

        // Highlight in green bold if the ingredient is superior
       if (FlaggedIngredientManager.isSuperiorIngredient(this, trimmedToken)) {
            dialogContent.setSpan(new android.text.style.ForegroundColorSpan(Color.parseColor("#15803D")), 
                    tokenStart, tokenEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            dialogContent.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 
                    tokenStart, tokenEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    // Build TextView to support Spannable formatting
    TextView messageView = new TextView(this);
    messageView.setText(dialogContent);
    messageView.setTextSize(15f);
    messageView.setPadding(48, 32, 48, 16);
    messageView.setLineSpacing(1.2f, 1.1f);

    new AlertDialog.Builder(this)
            .setTitle(title)
            .setView(messageView)
            .setPositiveButton("Close", null)
          .setNeutralButton("BUY!", (dialog, which) -> openWalmartSearch(altProduct.name))  
            .show();
}

    private void openBarcodeScanner() {
        Intent intent = new Intent(this, BarcodeScannerActivity.class);
        barcodeLauncher.launch(intent);
    }

    private void lookupBarcodeAndSearch(String gtin) {
        if (progress != null) progress.setVisibility(View.VISIBLE);

        new Thread(() -> {
            String productName = gtin;

            try {
                // Try GTIN spellings: as-scanned, then with leading zeros stripped
                // (USDA stores gtinUpc as printed, e.g. 12-digit UPC-A).
                JSONObject match = null;
                for (String candidate : gtinCandidates(gtin)) {
                    match = findFoodByGtin(candidate);
                    if (match != null) break;
                }

                if (match != null) {
                    String description = match.optString("description", "");
                    String brand = match.optString("brandOwner", match.optString("brandName", ""));

                    if (!description.isEmpty()) {
                        productName = description;
                        if (!brand.isEmpty()) {
                            productName = description + " (" + brand + ")";
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            final String finalProductName = productName;
            runOnUiThread(() -> {
                if (progress != null) progress.setVisibility(View.GONE);
                searchBox.setText(finalProductName);
                search();
            });
        }).start();
    }

    /** GTIN spellings worth trying against the USDA index. */
    private List<String> gtinCandidates(String gtin) {
        List<String> out = new ArrayList<>();
        String digits = gtin.replaceAll("\\D", "");
        if (!digits.isEmpty()) out.add(digits);
        String stripped = digits.replaceFirst("^0+", "");
        if (!stripped.isEmpty() && !stripped.equals(digits)) out.add(stripped);
        return out;
    }

    /**
     * Queries USDA for a GTIN and returns the food whose gtinUpc matches exactly
     * (comparing without leading zeros), or null when nothing matches. Falls back
     * to the first branded result with an ingredient list when no exact gtinUpc
     * match exists.
     */
    private JSONObject findFoodByGtin(String gtin) throws Exception {
        String urlString = "https://api.nal.usda.gov/fdc/v1/foods/search?query="
                + URLEncoder.encode(gtin, "UTF-8")
                + "&dataType=Branded"
                + "&pageSize=10"
                + "&api_key=" + BuildConfig.USDA_API_KEY;

        HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "DirtyIngredients/1.0 (Android food ingredient screening app)");

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

private void setupCategoryFilterPanel() {
    if (categoryCheckboxContainer == null) return;
    categoryCheckboxContainer.removeAllViews();

    List<String> categories = new ArrayList<>();

    // Read categories dynamically from JSON asset
    try (InputStream is = getAssets().open("flagged_ingredients.json");
         BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
        
        StringBuilder jsonBuilder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            jsonBuilder.append(line);
        }

        JSONObject rootJson = new JSONObject(jsonBuilder.toString());
        Iterator<String> keys = rootJson.keys();
        while (keys.hasNext()) {
            categories.add(keys.next());
        }
    } catch (Exception e) {
        Log.e("MainActivity", "Failed to load categories for checkboxes", e);
    }

    for (String category : categories) {
        CheckBox checkBox = new CheckBox(this);
        checkBox.setText(category);
        checkBox.setTextSize(12f);

        // Check preference manager (or SharedPreferences) for sticky state
        boolean isEnabled = CategoryPreferenceManager.isCategoryEnabled(this, category);
        checkBox.setChecked(isEnabled);

        checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            CategoryPreferenceManager.setCategoryEnabled(MainActivity.this, category, isChecked);
        });

        com.google.android.flexbox.FlexboxLayout.LayoutParams params =
                new com.google.android.flexbox.FlexboxLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
        params.setMargins(0, 0, 16, 4);
        checkBox.setLayoutParams(params);

        categoryCheckboxContainer.addView(checkBox);
    }
}


    public enum MatchQuality {
        EXACT,
        CLOSE_ENOUGH,
        NONE
    }

    public static class ProductResult {
    public boolean found;
    public String name, brand, ingredients;
    public String foodCategory = "";
    public String gtinUpc = "";
    public String brandName = "";
    public String brandOwner = "";
    public MatchQuality matchQuality;
    public FlaggedIngredientManager.MatchResult matchResult;
    public Set<String> flagged = new HashSet<>();

    public ProductResult(boolean found, String name, String brand, String ingredients,
                         FlaggedIngredientManager.MatchResult matchResult, MatchQuality matchQuality) {
        this.found = found;
        this.name = name;
        this.brand = brand;
        this.ingredients = ingredients;
        this.matchResult = matchResult;
        this.matchQuality = matchQuality;

        // Reset and strictly populate only active matches
        this.flagged.clear();
        if (matchResult != null && matchResult.hasMatches()) {
            for (List<String> items : matchResult.categoryMap.values()) {
                this.flagged.addAll(items);
            }
        }
    }

    public ProductResult(boolean found, String name, String brand, String ingredients,
                         FlaggedIngredientManager.MatchResult matchResult) {
        this(found, name, brand, ingredients, matchResult, MatchQuality.NONE);
    }

    public static ProductResult notFound() {
        return new ProductResult(false, "", "", "", new FlaggedIngredientManager.MatchResult(), MatchQuality.NONE);
    }
}


}