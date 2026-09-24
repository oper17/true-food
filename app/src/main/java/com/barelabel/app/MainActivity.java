package com.barelabel.app;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
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



import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.browser.customtabs.CustomTabColorSchemeParams;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;

import com.google.android.flexbox.FlexboxLayout;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.barelabel.app.model.AlternateSearchResult;
import com.barelabel.app.model.ProductResult;
import com.barelabel.app.model.ScannedProduct;
import com.barelabel.app.network.UsdaApiClient;
import com.barelabel.app.FlaggedIngredientManager;
import com.barelabel.app.search.CleanAlternateFinder;
import com.barelabel.app.ui.AlternatesCardController;
import com.barelabel.app.ui.CompareViewBuilder;
import com.barelabel.app.ui.ProductDetailDialog;
import com.barelabel.app.util.StringNormalizer;

import android.view.ViewGroup;
import android.widget.CheckBox;


public class MainActivity extends AppCompatActivity {

    // Member Variable Declarations
    private EditText searchBox;
    private Button searchButton;
    private ProgressBar progress;
    private TextView statusText, verdictText, flaggedText, ingredientsText;
    private LinearLayout resultCard;

    // Separate Panels & Expandable Ingredients
    private View ingredientsCard;
    private TextView toggleIngredientsButton;
    private TextView productTitleText;
    private android.widget.ImageView productImageView;
    private TextView matchNoticeText;
	private com.google.android.flexbox.FlexboxLayout categoryCheckboxContainer;

    // Filter collapse: full checkbox panel vs. compact summary bar
    private LinearLayout categoryFilterPanel;
    private LinearLayout filterSummaryBar;
    private TextView filterSummaryText;

    // Sticky results header overlay
    private LinearLayout stickyResultsBar;
    private View compareTabContent;
    private View historyTabContent;
    private com.barelabel.app.ui.HistoryTabController historyTabController;
    private com.barelabel.app.ui.CompareTabController compareTabController;
    private com.google.android.material.tabs.TabLayout mainTabLayout;
    private CheckBox verdictCompareBox;
    private ProductResult currentPrimaryResult;
    private final LinkedHashMap<String, ProductResult> comparePicks = new LinkedHashMap<>();
    private boolean syncingCompareUi;
    private TextView stickyResultsText;

    // Verdict pass/fail chips on the primary product card
    private FlexboxLayout verdictChipsContainer;

    // Cached flagged-category names from flagged_ingredients.json
    private List<String> flaggedCategoriesCache = null;

    private boolean isIngredientsExpanded = false;

    // Collaborators: USDA network, clean-alternate search, and the alternates card UI.
    private UsdaApiClient usdaApiClient;
    private CleanAlternateFinder alternateFinder;
    private AlternatesCardController alternatesController;
    private FoodAutoCompleteManager autoCompleteManager;
    // USDA record behind the last tapped autocomplete suggestion (0 = none/typed query).
    private long selectedSuggestionFdcId = 0;
    private String selectedSuggestionLabel = "";

    private final Set<String> superiorTerms = new HashSet<>();

    private final ActivityResultLauncher<Intent> barcodeLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                boolean success = false;
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String scannedBarcode = result.getData().getStringExtra(BarcodeScannerActivity.EXTRA_BARCODE);
                    if (scannedBarcode != null && !scannedBarcode.trim().isEmpty()) {
                        success = true;
                        lookupBarcodeAndSearch(scannedBarcode.trim());
                    }
                }
                AnalyticsTracker.barcodeScanned(success);
            }
    );
@Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    setTitle("BareLabel");

    AnalyticsTracker.init(this);

    // 1. Bind UI Components
    bindViews();

    // 2. Load Superior Terms Engine Set
    loadSuperiorTerms();

    // 3. Collaborators: USDA client, alternate search, alternates card UI.
    usdaApiClient = new UsdaApiClient(this);
    alternateFinder = new CleanAlternateFinder(this, usdaApiClient);
    alternatesController = new AlternatesCardController(
            this,
            findViewById(R.id.alternatesCard),
            findViewById(R.id.alternatesTitle),
            findViewById(R.id.alternatesText),
            (SwitchCompat) findViewById(R.id.preferOrganicSwitch),
            product -> openShoppingSearch(product));
    alternatesController.setSuperiorTerms(superiorTerms);
    alternatesController.setOnClearFilters(this::clearAllFiltersAndSearch);
    alternatesController.setOnUncheckFilter(category -> {
        uncheckFilterCategory(category);
        search();
    });

    // 4. Setup Barcode Scanner Button
    ImageButton scanBarcodeButton = findViewById(R.id.scanBarcodeButton);
    if (scanBarcodeButton != null) {
        scanBarcodeButton.setOnClickListener(v -> openBarcodeScanner());
    }

setupCategoryFilterPanel();
    // 4c. Main tabs: Search | Compare | History. Search is the default landing tab.
    mainTabLayout = findViewById(R.id.mainTabLayout);
    com.google.android.material.tabs.TabLayout tabLayout = mainTabLayout;
    View searchTabContent = findViewById(R.id.mainRootLayout);
    compareTabContent = findViewById(R.id.compareTabContent);
    historyTabContent = findViewById(R.id.historyTabContent);
    if (tabLayout != null && searchTabContent != null) {
        historyTabController = new com.barelabel.app.ui.HistoryTabController(
                this, historyTabContent);
        compareTabController = new com.barelabel.app.ui.CompareTabController(
                this, compareTabContent);
        compareTabController.setCompareActionListener(
                new CompareViewBuilder.CompareActionListener() {
                    @Override
                    public void onBuy(ScannedProduct p) {
                        ProductResult pr = new ProductResult();
                        pr.found = true;
                        pr.name = p.name;
                        pr.brand = p.brand;
                        openShoppingSearch(pr);
                    }

                    @Override
                    public boolean onSave(ScannedProduct p) {
                        return saveScannedToHistory(p);
                    }
                });
        final View searchContent = searchTabContent;
        tabLayout.addOnTabSelectedListener(
                new com.google.android.material.tabs.TabLayout.OnTabSelectedListener() {
                    @Override
                    public void onTabSelected(
                            com.google.android.material.tabs.TabLayout.Tab tab) {
                        showTab(tab.getPosition(), searchContent);
                    }
                    @Override
                    public void onTabUnselected(
                            com.google.android.material.tabs.TabLayout.Tab tab) {}
                    @Override
                    public void onTabReselected(
                            com.google.android.material.tabs.TabLayout.Tab tab) {}
                });
        showTab(0, searchContent);
    }

    // Compare boxes: one on the verdict card, one per clean-alternate row.
    // Checking any 2 auto-opens the Compare tab with the pair.
    if (verdictCompareBox != null) {
        verdictCompareBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (syncingCompareUi || currentPrimaryResult == null) return;
            AnalyticsTracker.compareCheckboxToggled(isChecked, "verdict_card");
            toggleComparePick(currentPrimaryResult);
        });
    }
    if (alternatesController != null) {
        alternatesController.setComparePickListener(
                new AlternatesCardController.ComparePickListener() {
                    @Override
                    public boolean isSelectedForCompare(ProductResult pr) {
                        return comparePicks.containsKey(compareKey(pr));
                    }

                    @Override
                    public void onToggleComparePick(ProductResult pr) {
                        toggleComparePick(pr);
                    }
                });
    }
    if (alternatesController != null) {
        alternatesController.setSaveListener(p -> {
            boolean saved = saveProductToHistory(p);
            if (saved) AnalyticsTracker.productSaved("alternate");
            return saved;
        });
    }
    // 5. Attach AutoComplete Manager
    // Unbranded completions (offline dictionary) take rank 1-2; USDA fills the rest.
    SuggestionProvider suggestionProvider = new CombinedSuggestionProvider(
            new UnbrandedSuggestionProvider(this), new UsdaSuggestionProvider(this));
    autoCompleteManager = new FoodAutoCompleteManager(this, suggestionProvider);
    autoCompleteManager.attachToEditText(searchBox);
    autoCompleteManager.setOnSuggestionSelected(suggestion -> {
        selectedSuggestionFdcId = suggestion.fdcId;
        selectedSuggestionLabel = suggestion.label;
    });
    autoCompleteManager.setOnRecentSearchSelected(query -> {
        selectedSuggestionFdcId = 0;
        selectedSuggestionLabel = "";
        searchBox.setText(query);
        search();
    });

    // Filter summary bar: tap to expand the full filter panel again.
    if (filterSummaryBar != null) {
        filterSummaryBar.setOnClickListener(v -> {
            if (categoryFilterPanel != null) {
                categoryFilterPanel.setVisibility(View.VISIBLE);
            }
            filterSummaryBar.setVisibility(View.GONE);
        });
    }

    // Sticky results header: appears once the alternates card scrolls into
    // view; tapping it scrolls back to the card.
    NestedScrollView mainRoot = findViewById(R.id.mainRootLayout);
    View alternatesCardView = findViewById(R.id.alternatesCard);
    if (mainRoot != null && alternatesCardView != null && stickyResultsBar != null) {
        mainRoot.setOnScrollChangeListener((NestedScrollView.OnScrollChangeListener) (v, scrollX, scrollY, oldX, oldY) -> {
            boolean show = alternatesCardView.getVisibility() == View.VISIBLE
                    && scrollY > alternatesCardView.getTop();
            stickyResultsBar.setVisibility(show ? View.VISIBLE : View.GONE);
        });
        stickyResultsBar.setOnClickListener(v ->
                mainRoot.smoothScrollTo(0, Math.max(0, alternatesCardView.getTop())));
    }

    // 6. Setup Primary Retail Buy Button
    Button buyButton = findViewById(R.id.walmartButton);
    if (buyButton != null) {
        buyButton.setOnClickListener(v -> {
            String currentQuery = searchBox.getText().toString().trim();
            if (!currentQuery.isEmpty()) {
                AnalyticsTracker.buyTapped("search_box");
                openShoppingSearch(currentQuery);
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
        productImageView = findViewById(R.id.productImageView);
        matchNoticeText = findViewById(R.id.matchNoticeText);

        ingredientsCard = findViewById(R.id.ingredientsCard);
        toggleIngredientsButton = findViewById(R.id.toggleIngredientsButton);
		categoryCheckboxContainer = findViewById(R.id.categoryCheckboxContainer);
        categoryFilterPanel = findViewById(R.id.categoryFilterPanel);
        filterSummaryBar = findViewById(R.id.filterSummaryBar);
        filterSummaryText = findViewById(R.id.filterSummaryText);
        stickyResultsBar = findViewById(R.id.stickyResultsBar);
        stickyResultsText = findViewById(R.id.stickyResultsText);
        verdictChipsContainer = findViewById(R.id.verdictChipsContainer);
        verdictCompareBox = findViewById(R.id.verdictCompareBox);
    }

    private void loadSuperiorTerms() {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(getAssets().open("superior_ingredients.txt")))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = StringNormalizer.normalize(line);
                if (!line.isEmpty() && !line.startsWith("#")) {
                    superiorTerms.add(line);
                }
            }
        } catch (Exception e) {
            Toast.makeText(this, "Could not load superior ingredient list.", Toast.LENGTH_SHORT).show();
        }
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
        Set<String> brandTokens = StringNormalizer.wordTokens(product.brandName + " " + product.brandOwner);
        if (brandTokens.isEmpty()) return false;
        for (String token : StringNormalizer.wordTokens(query)) {
            if (token.length() >= 4 && brandTokens.contains(token)) {
                return false; // query names the brand -> branded product search
            }
        }
        return true;
    }

    private void search() {
        clearComparePicks();
        // Suggestions are no longer needed once the user commits to a search.
        if (autoCompleteManager != null) {
            autoCompleteManager.dismissSuggestions();
        }
        final String product = searchBox.getText().toString().trim();
        if (TextUtils.isEmpty(product)) {
            searchBox.setError("Enter a food product name");
            return;
        }
        AnalyticsTracker.searchPerformed(product.length());
        RecentSearches.add(this, product);

        // Collapse the filter panel into its summary bar once a search is issued.
        if (categoryFilterPanel != null && filterSummaryBar != null) {
            categoryFilterPanel.setVisibility(View.GONE);
            filterSummaryBar.setVisibility(View.VISIBLE);
            updateFilterSummary();
        }
        if (stickyResultsBar != null) {
            stickyResultsBar.setVisibility(View.GONE);
        }

        progress.setVisibility(ProgressBar.VISIBLE);
        searchButton.setEnabled(false);
        resultCard.setVisibility(LinearLayout.GONE);
        alternatesController.resetToggle();
        statusText.setText("Searching USDA FoodData Central…");
        ingredientsText.setText("");

        new Thread(() -> {
            try {
                // 1. Fetch primary product. A tapped USDA suggestion carries its
                //    fdcId, so fetch that exact record instead of re-running a
                //    fuzzy text search on the display label.
                long fdcId = selectedSuggestionLabel.equals(product)
                        ? selectedSuggestionFdcId : 0;
                final boolean fetchedById = fdcId > 0;
                ProductResult primaryResult = fetchedById
                        ? usdaApiClient.fetchFoodById(fdcId)
                        : usdaApiClient.searchPrimary(product);
                selectedSuggestionFdcId = 0;
                selectedSuggestionLabel = "";

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

                // 4. Fetch clean candidates (+ which flagged categories blocked the rest)
                AlternateSearchResult altSearch = alternateFinder.findCleanAlternates(
                        foodType, apiCategory, categoryIntent, primaryResult, product);
                List<ProductResult> rawAlternates = altSearch.alternates;
                alternatesController.setPool(rawAlternates, StringNormalizer.wordTokens(product),
                        categoryIntent);

                // 5. Send to UI (the controller ranks each stack itself)
                final String finalCategory = foodType;
                final boolean finalCategoryIntent = categoryIntent;
                final Set<String> finalFlaggedCategories = altSearch.flaggedCategories;
                final Map<String, Integer> finalReliefCounts = altSearch.singleFilterBlockCounts;
                runOnUiThread(() -> showResult(primaryResult, finalCategory, finalCategoryIntent,
                        finalFlaggedCategories, finalReliefCounts, fetchedById));

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


    private void openShoppingSearch(String query) {
        openShoppingUrl(ShoppingUrlBuilder.buildSearchUrl(query));
    }

    private void openShoppingSearch(ProductResult product) {
        com.barelabel.app.images.ProductImageResolver.OffProductInfo off =
                com.barelabel.app.images.ProductImageResolver.getCached(
                        this, product == null ? "" : product.gtinUpc);
        openShoppingUrl(ShoppingUrlBuilder.buildProductUrl(product, off));
    }

    private void openShoppingUrl(String shoppingUrl) {
        CustomTabColorSchemeParams colorParams = new CustomTabColorSchemeParams.Builder()
                .setToolbarColor(ContextCompat.getColor(this, R.color.cream))
                .build();

        CustomTabsIntent customTabsIntent = new CustomTabsIntent.Builder()
                .setDefaultColorSchemeParams(colorParams)
                .setShowTitle(true)
                .setUrlBarHidingEnabled(true)
                .build();

        try {
            customTabsIntent.launchUrl(this, Uri.parse(shoppingUrl));
        } catch (Exception e) {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(shoppingUrl));
            startActivity(browserIntent);
        }
    }


    private void showResult(ProductResult result, String foodType, boolean categoryIntent,
                            Set<String> flaggedCategories, Map<String, Integer> reliefCounts,
                            boolean fetchedById) {
        currentPrimaryResult = result;
        clearComparePicks();
    // Unbranded category search (e.g. "cookies"): the top hit is just one random
    // branded product in the category, not what the user asked about, so hide the
    // primary verdict card and show only the clean-choices card.
    if (categoryIntent) {
        if (resultCard != null) resultCard.setVisibility(View.GONE);
        alternatesController.show(foodType, true, true, flaggedCategories, reliefCounts,
                countActiveFilters());
        updateStickyBar();
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

    Button buyButton = findViewById(R.id.walmartButton);
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
        if (productImageView != null) productImageView.setVisibility(View.GONE);
        resultCard.setBackgroundResource(R.drawable.verdict_dirty);
        verdictText.setText("? PRODUCT NOT FOUND");
        verdictText.setTextColor(colorMuted);
        if (flaggedTitle != null) flaggedTitle.setVisibility(View.GONE);
        if (flaggedText != null) flaggedText.setVisibility(View.GONE);
        if (buyButton != null) buyButton.setVisibility(View.GONE);
        bindVerdictChips(null);
        alternatesController.hide();
        return;
    }

    String displayName = result.name;
    if (!TextUtils.isEmpty(result.brand)) {
        displayName += " (" + result.brand + ")";
    }
    if (productTitleText != null) {
        productTitleText.setText(StringNormalizer.toTitleCase(displayName));
        productTitleText.setVisibility(View.VISIBLE);
    }
    // Product thumbnail + friendlier OFF name (conditional: hidden/absent when unavailable).
    if (productImageView != null) {
        productImageView.setVisibility(View.GONE);
        productImageView.setTag(result.gtinUpc);
        final String verdictGtin = result.gtinUpc;
        com.barelabel.app.images.ProductImageResolver.resolve(
                this, verdictGtin, info -> {
                    if (!java.util.Objects.equals(verdictGtin, productImageView.getTag())) return;
                    if (info == null) return;
                    if (info.hasImage()) {
                        productImageView.setVisibility(View.VISIBLE);
                        com.bumptech.glide.Glide.with(this)
                                .load(info.imageUrl)
                                .centerCrop()
                                .into(productImageView);
                    }
                    if (info.hasName() && productTitleText != null) {
                        productTitleText.setText(
                                StringNormalizer.toTitleCase(info.displayName()));
                    }
                });
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
        if (buyButton != null) buyButton.setVisibility(View.GONE);
        bindVerdictChips(null);
        alternatesController.hide();
        return;
    }

    boolean isClean = result.flagged == null || result.flagged.isEmpty();

    // Persist this scan to history (deduplicated, newest-first). Disk I/O off the UI thread.
    final ProductResult scannedResult = result;
    new Thread(() -> ScanHistoryRepository.saveScan(MainActivity.this, scannedResult)).start();

    // 1. BUY Button Visibility
    if (buyButton != null) {
        if (isClean) {
            buyButton.setVisibility(View.VISIBLE);
            buyButton.setText("BUY!");
            buyButton.setOnClickListener(v -> {
                AnalyticsTracker.buyTapped("primary_product");
                openShoppingSearch(result);
            });
        } else {
            buyButton.setVisibility(View.GONE);
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

    // 3. Verdict chips: per-category pass/fail so the verdict shows its work.
    bindVerdictChips(result);

    // 4. Clean Alternates Section
    alternatesController.show(foodType, categoryIntent, isClean, flaggedCategories,
            reliefCounts, countActiveFilters());
    updateStickyBar();
}

/** Cached flagged-category names from flagged_ingredients.json. */
private List<String> getFlaggedCategories() {
    if (flaggedCategoriesCache == null) {
        flaggedCategoriesCache = new ArrayList<>();
        try (InputStream is = getAssets().open("flagged_ingredients.json");
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder jsonBuilder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                jsonBuilder.append(line);
            }
            JSONObject rootJson = new JSONObject(jsonBuilder.toString());
            Iterator<String> keys = rootJson.keys();
            while (keys.hasNext()) {
                flaggedCategoriesCache.add(keys.next());
            }
        } catch (Exception e) {
            Log.e("MainActivity", "Failed to load flagged categories", e);
        }
    }
    return flaggedCategoriesCache;
}

/** Number of currently enabled flagged-category filters. */
private int countActiveFilters() {
    int count = 0;
    if (categoryCheckboxContainer != null) {
        for (int i = 0; i < categoryCheckboxContainer.getChildCount(); i++) {
            View child = categoryCheckboxContainer.getChildAt(i);
            if (child instanceof CheckBox && ((CheckBox) child).isChecked()) {
                count++;
            }
        }
    }
    return count;
}

private void updateFilterSummary() {
    if (filterSummaryText == null) return;
    int active = countActiveFilters();
    filterSummaryText.setText("Filters • " + active + " active");
}

/** Disables every flagged-category filter and re-runs the current search. */
private void clearAllFiltersAndSearch() {
    if (categoryCheckboxContainer != null) {
        for (int i = 0; i < categoryCheckboxContainer.getChildCount(); i++) {
            View child = categoryCheckboxContainer.getChildAt(i);
            if (child instanceof CheckBox) {
                ((CheckBox) child).setChecked(false);
            }
        }
    }
    search();
}

/** Unchecks one filter category (smart relief) — the checkbox listener persists it. */
private void uncheckFilterCategory(String category) {
    if (categoryCheckboxContainer == null || category == null) return;
    for (int i = 0; i < categoryCheckboxContainer.getChildCount(); i++) {
        View child = categoryCheckboxContainer.getChildAt(i);
        if (child instanceof CheckBox
                && category.equals(((CheckBox) child).getText().toString())) {
            ((CheckBox) child).setChecked(false);
            return;
        }
    }
}

/**
 * Per-category pass/fail chips under the verdict: green "✓ No X" for enabled
 * categories with no flagged match, red "⚠ X" for the ones that fired.
 */
private void bindVerdictChips(ProductResult result) {
    if (verdictChipsContainer == null) return;
    verdictChipsContainer.removeAllViews();
    if (result == null || !result.found || TextUtils.isEmpty(result.ingredients.trim())) {
        verdictChipsContainer.setVisibility(View.GONE);
        return;
    }
    Set<String> failed = new HashSet<>();
    if (result.matchResult != null && result.matchResult.categoryMap != null) {
        failed.addAll(result.matchResult.categoryMap.keySet());
    }
    boolean any = false;
    for (String category : getFlaggedCategories()) {
        if (!CategoryPreferenceManager.isCategoryEnabled(this, category)) continue;
        any = true;
        boolean isFailed = failed.contains(category);
        TextView chip = new TextView(this);
        chip.setText(isFailed ? "⚠ " + category
                : "✓ No " + category.toLowerCase(Locale.US));
        chip.setTextSize(12f);
        chip.setTextColor(Color.parseColor(isFailed ? "#991B1B" : "#166534"));
        chip.setBackgroundResource(isFailed ? R.drawable.chip_dirty_background
                : R.drawable.chip_clean_background);
        int hPad = dp(10), vPad = dp(5);
        chip.setPadding(hPad, vPad, hPad, vPad);
        FlexboxLayout.LayoutParams params = new FlexboxLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, dp(6), dp(6));
        chip.setLayoutParams(params);
        verdictChipsContainer.addView(chip);
    }
    verdictChipsContainer.setVisibility(any ? View.VISIBLE : View.GONE);
}

/** Refreshes the sticky results bar from the alternates controller's state. */
private void updateStickyBar() {
    if (stickyResultsBar == null || stickyResultsText == null) return;
    if (!alternatesController.isCardShowing()) {
        stickyResultsBar.setVisibility(View.GONE);
        return;
    }
    stickyResultsText.setText(alternatesController.getHeading()
            + " • Exact (" + alternatesController.getExactCount() + ")"
            + " • More (" + alternatesController.getMoreCount() + ")");
}

/** Key identifying a compare pick (verdict card or alternate row). */
private String compareKey(ProductResult p) {
    String n = p.name == null ? "" : p.name.trim().toLowerCase(Locale.US);
    String b = p.brand == null ? "" : p.brand.trim().toLowerCase(Locale.US);
    return n + "|" + b;
}

/** Toggle a search result (verdict card or alternate row) in the compare picks (max 2). */
private void toggleComparePick(ProductResult p) {
    if (p == null) return;
    String key = compareKey(p);
    if (comparePicks.containsKey(key)) {
        comparePicks.remove(key);
    } else if (comparePicks.size() >= 2) {
        Toast.makeText(this, "You can compare at most 2 products",
                Toast.LENGTH_SHORT).show();
    } else {
        comparePicks.put(key, p);
    }
    syncCompareBoxes();
    if (comparePicks.size() == 2) {
        openCompareForPicks();
    }
}

/** Re-check every compare box from the pick set (call after any toggle/clear). */
private void syncCompareBoxes() {
    syncingCompareUi = true;
    try {
        if (verdictCompareBox != null) {
            verdictCompareBox.setChecked(currentPrimaryResult != null
                    && comparePicks.containsKey(compareKey(currentPrimaryResult)));
        }
        if (alternatesController != null) {
            alternatesController.syncCompareBoxes();
        }
    } finally {
        syncingCompareUi = false;
    }
}

private void clearComparePicks() {
    if (compareTabController != null) compareTabController.clearExternal();
    if (comparePicks.isEmpty()) return;
    comparePicks.clear();
    syncCompareBoxes();
}

/** Save a search result to history. Returns true when actually saved. */
private boolean saveProductToHistory(ProductResult p) {
    if (p == null || !p.found || TextUtils.isEmpty(p.ingredients)
            || p.ingredients.trim().isEmpty()) {
        return false;
    }
    ScanHistoryRepository.saveScan(this, p);
    Toast.makeText(this, "Saved to history", Toast.LENGTH_SHORT).show();
    return true;
}

private boolean saveScannedToHistory(ScannedProduct p) {
    if (p == null) return false;
    ProductResult pr = new ProductResult();
    pr.found = true;
    pr.name = p.name;
    pr.brand = p.brand;
    pr.ingredients = p.ingredients;
    if (p.flagged != null) pr.flagged.addAll(p.flagged);
    pr.gtinUpc = p.gtin == null ? "" : p.gtin;
    boolean saved = saveProductToHistory(pr);
    if (saved) AnalyticsTracker.productSaved("compare");
    return saved;
}

/** Two picks made: pin the pair on the Compare tab and switch to it. */
private void openCompareForPicks() {
    if (comparePicks.size() != 2 || compareTabController == null) return;
    List<ProductResult> picked = new ArrayList<>(comparePicks.values());
    compareTabController.compareExternal(
            ScannedProduct.fromProductResult(picked.get(0)),
            ScannedProduct.fromProductResult(picked.get(1)));
    if (mainTabLayout != null) {
        if (mainTabLayout.getSelectedTabPosition() == 1) {
            compareTabController.refresh();
        } else {
            com.google.android.material.tabs.TabLayout.Tab tab = mainTabLayout.getTabAt(1);
            if (tab != null) tab.select();
        }
    }
}

/** Switch the visible tab pane: 0 = Search, 1 = Compare, 2 = History. */
private void showTab(int position, View searchContent) {
    searchContent.setVisibility(position == 0 ? View.VISIBLE : View.GONE);
    if (compareTabContent != null) {
        compareTabContent.setVisibility(position == 1 ? View.VISIBLE : View.GONE);
    }
    if (historyTabContent != null) {
        historyTabContent.setVisibility(position == 2 ? View.VISIBLE : View.GONE);
    }
    // The sticky results bar only belongs to the search tab.
    if (stickyResultsBar != null && position != 0) {
        stickyResultsBar.setVisibility(View.GONE);
    }
    if (position == 1 && compareTabController != null) {
        AnalyticsTracker.compareTabOpened();
        compareTabController.refresh();
    }
    if (position == 2 && historyTabController != null) {
        AnalyticsTracker.historyOpened();
        historyTabController.refresh();
    }
}

private int dp(int dps) {
    return Math.round(dps * getResources().getDisplayMetrics().density);
}

private void showFullIngredientsDialog(ProductResult product) {
    String title = product.name;
    if (!TextUtils.isEmpty(product.brand)) {
        title += " (" + product.brand + ")";
    }
    ProductDetailDialog.show(this, title, product.ingredients, product.flagged);
}



    private void openBarcodeScanner() {
        Intent intent = new Intent(this, BarcodeScannerActivity.class);
        barcodeLauncher.launch(intent);
    }

    private void lookupBarcodeAndSearch(String gtin) {
        clearComparePicks();
        if (progress != null) progress.setVisibility(View.VISIBLE);

        new Thread(() -> {
            String productName = gtin;

            try {
                // Try GTIN spellings: as-scanned, then with leading zeros stripped
                // (USDA stores gtinUpc as printed, e.g. 12-digit UPC-A).
                JSONObject match = null;
                for (String candidate : gtinCandidates(gtin)) {
                    match = usdaApiClient.findFoodByGtin(candidate);
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

private void setupCategoryFilterPanel() {
    if (categoryCheckboxContainer == null) return;
    categoryCheckboxContainer.removeAllViews();

    List<String> categories = getFlaggedCategories();

    for (String category : categories) {
        CheckBox checkBox = new CheckBox(this);
        checkBox.setText(category);
        checkBox.setTextSize(12f);

        // Check preference manager (or SharedPreferences) for sticky state
        boolean isEnabled = CategoryPreferenceManager.isCategoryEnabled(this, category);
        checkBox.setChecked(isEnabled);

        checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            CategoryPreferenceManager.setCategoryEnabled(MainActivity.this, category, isChecked);
            AnalyticsTracker.categoryToggled(category, isChecked);
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
}
