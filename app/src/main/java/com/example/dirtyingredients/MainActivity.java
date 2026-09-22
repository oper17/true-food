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
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

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
                if (!line.isEmpty() && !line.startsWith("#")) {
                    flaggedIngredients.add(line);
                }
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
                List<AlternateRanker.RankedProduct> rankedAlternates = AlternateRanker.rankAndFilter(rawAlternates, superiorTerms);

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

    private ProductResult searchUSDA3(String productName) throws Exception {
        String apiKey = BuildConfig.USDA_API_KEY;
        String q = URLEncoder.encode(productName, "UTF-8");

        String url = "https://api.nal.usda.gov/fdc/v1/foods/search"
                + "?api_key=" + apiKey
                + "&query=" + q
                + "&dataType=Branded"
                + "&pageSize=10";

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestMethod("GET");
        c.setRequestProperty("User-Agent", "DirtyIngredients/1.0 (Android food ingredient screening app)");

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

    private List<ProductResult> searchUSDAAlternates(String foodCategory, ProductResult primaryResult) throws Exception {
        List<ProductResult> alternates = new ArrayList<>();

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
                + "&pageSize=30";

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

        Set<String> seenProductKeys = new HashSet<>();
        String primaryKey = normalize(primaryResult.name) + "|" + normalize(primaryResult.brand);
        seenProductKeys.add(primaryKey);

        for (int i = 0; i < foods.length(); i++) {
            JSONObject f = foods.getJSONObject(i);
            String name = f.optString("description", "");
            String brand = f.optString("brandOwner", "");
            String ingredients = f.optString("ingredients", "");

            if (TextUtils.isEmpty(ingredients.trim())) {
                continue;
            }

            String productKey = normalize(name) + "|" + normalize(brand);
            if (seenProductKeys.contains(productKey)) {
                continue;
            }
            seenProductKeys.add(productKey);

            Set<String> flagged = findFlaggedIngredients(ingredients);
            ProductResult altResult = new ProductResult(true, name, brand, ingredients, flagged);
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

    private void showResult(ProductResult result, String foodType, List<AlternateRanker.RankedProduct> alternates) {
        resultCard.setVisibility(View.VISIBLE);

        int colorClean = Color.parseColor("#166534");
        int colorDirty = Color.parseColor("#991B1B");
        int colorMuted = Color.parseColor("#4B5563");

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

    private void showAlternateIngredientsDialog(ProductResult altProduct) {
        String title = altProduct.name;
        if (!TextUtils.isEmpty(altProduct.brand)) {
            title += " (" + altProduct.brand + ")";
        }

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage("INGREDIENTS:\n\n" + altProduct.ingredients)
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
                String urlString = "https://api.nal.usda.gov/fdc/v1/foods/search?query="
                        + URLEncoder.encode(gtin, "UTF-8")
                        + "&api_key=" + BuildConfig.USDA_API_KEY;

                URL url = new URL(urlString);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                if (conn.getResponseCode() == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder builder = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        builder.append(line);
                    }
                    reader.close();

                    JSONObject responseJson = new JSONObject(builder.toString());
                    JSONArray foods = responseJson.optJSONArray("foods");

                    if (foods != null && foods.length() > 0) {
                        JSONObject firstFood = foods.getJSONObject(0);
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

            final String finalProductName = productName;
            runOnUiThread(() -> {
                if (progress != null) progress.setVisibility(View.GONE);
                searchBox.setText(finalProductName);
                search();
            });
        }).start();
    }

    public enum MatchQuality {
        EXACT,
        CLOSE_ENOUGH,
        NONE
    }

    public static class ProductResult {
        boolean found;
        String name, brand, ingredients;
        Set<String> flagged;
        public final MatchQuality matchQuality;

        ProductResult(boolean found, String name, String brand, String ingredients, Set<String> flagged, MatchQuality matchQuality) {
            this.found = found;
            this.name = name;
            this.brand = brand;
            this.ingredients = ingredients;
            this.flagged = flagged;
            this.matchQuality = matchQuality;
        }

        ProductResult(boolean found, String name, String brand, String ingredients, Set<String> flagged) {
            this(found, name, brand, ingredients, flagged, MatchQuality.NONE);
        }

        static ProductResult notFound() {
            return new ProductResult(false, "", "", "", new TreeSet<>(), MatchQuality.NONE);
        }
    }
}