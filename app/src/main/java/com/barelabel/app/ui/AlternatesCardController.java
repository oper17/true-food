package com.barelabel.app.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.barelabel.app.AlternateRanker;
import com.barelabel.app.AnalyticsTracker;
import com.barelabel.app.FlaggedIngredientManager;
import com.barelabel.app.R;
import com.barelabel.app.model.ProductResult;
import com.barelabel.app.util.StringNormalizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Owns the "clean alternates" card, rendered as two stacks:
 * <ul>
 *   <li><b>Exact matches</b> (green panel) — clean products passing the
 *       exact-match hard filter: for non-branded searches the description must
 *       contain ALL query tokens (any order); for branded searches any single
 *       query token suffices.</li>
 *   <li><b>More clean options</b> (blue panel) — the remaining clean products
 *       from the scanned pool.</li>
 * </ul>
 * Each stack is a list of tappable rows: name / brand / clean-summary, expanding
 * inline to the full ingredient list (superior ingredients highlighted) plus a
 * Buy link. The prefer-organic switch re-ranks both stacks locally — no new
 * network request.
 */
public class AlternatesCardController {

    /** Called when the user taps Buy in an expanded alternate row. */
    public interface BuyListener {
        void onBuy(ProductResult product);
    }

    private final AppCompatActivity activity;
    private final View card;
    private final TextView title;
    private final TextView text;
    private final View exactPanel;
    private final TextView exactTitle;
    private final LinearLayout exactList;
    private final View morePanel;
    private final TextView moreTitle;
    private final LinearLayout moreList;
    private final View organicRow;
    private final SwitchCompat organicSwitch;
    private final TextView organicStateText;
    private final Button clearFiltersButton;
    private final BuyListener buyListener;

    private boolean preferOrganic = false;
    private boolean updatingSwitch = false;
    private List<ProductResult> pool = new ArrayList<>();
    private List<ProductResult> exactPool = new ArrayList<>();
    private List<ProductResult> morePool = new ArrayList<>();
    private Set<String> queryTokens = new HashSet<>();
    private Set<String> superiorTerms = new HashSet<>();
    /**
     * True for non-branded (category) searches: the exact-match stack then
     * requires ALL query tokens in the description, not just any one.
     */
    private boolean requireAllTokens = false;
    /** Row keys (name|brand) currently expanded; preserved across re-ranks. */
    private final Set<String> expandedKeys = new HashSet<>();

    /** Host (MainActivity) tracks the max-2 compare picks across verdict + rows. */
    public interface ComparePickListener {
        boolean isSelectedForCompare(ProductResult p);
        void onToggleComparePick(ProductResult p);
    }

    private ComparePickListener comparePickListener;
    private final Map<String, android.widget.Button> compareBoxes = new HashMap<>();
    private boolean syncingCompareBoxes;

    /** Host persists an alternate to scan history. Returns true when saved. */
    public interface SaveListener {
        boolean onSaveProduct(ProductResult p);
    }

    private SaveListener saveListener;
    private Runnable onClearFilters;

    /** Host unchecks one filter category and re-searches (smart relief). */
    public interface OnUncheckFilterListener {
        void onUncheckFilter(String category);
    }

    private OnUncheckFilterListener onUncheckFilter;
    /** Programmatic bar of "Uncheck X (N)" suggestion buttons in the empty state. */
    private LinearLayout filterSuggestionBar;

    // Last shown context, so the toggle can re-render without a new search.
    private String currentFoodType = "";
    private boolean currentCategoryIntent = false;
    private boolean currentAlternatesClean = true;
    private Set<String> currentFlaggedCategories = new LinkedHashSet<>();
    private Map<String, Integer> currentReliefCounts = new LinkedHashMap<>();
    private int currentActiveFilterCount = 0;
    private String currentHeading = "";
    private int lastExactCount = 0;
    private int lastMoreCount = 0;

    public AlternatesCardController(AppCompatActivity activity, View card, TextView title,
                                    TextView text, SwitchCompat organicSwitch,
                                    BuyListener buyListener) {
        this.activity = activity;
        this.card = card;
        this.title = title;
        this.text = text;
        this.organicSwitch = organicSwitch;
        this.buyListener = buyListener;

        this.exactPanel = card.findViewById(R.id.exactMatchesPanel);
        this.exactTitle = card.findViewById(R.id.exactMatchesTitle);
        this.exactList = card.findViewById(R.id.exactMatchesList);
        this.morePanel = card.findViewById(R.id.moreOptionsPanel);
        this.moreTitle = card.findViewById(R.id.moreOptionsTitle);
        this.moreList = card.findViewById(R.id.moreOptionsList);
        this.organicRow = activity.findViewById(R.id.preferOrganicRow);
        this.organicStateText = activity.findViewById(R.id.preferOrganicStateText);
        this.clearFiltersButton = card.findViewById(R.id.clearFiltersButton);

        View exactLegend = card.findViewById(R.id.exactLegendButton);
        if (exactLegend != null) {
            exactLegend.setOnClickListener(v -> showScoreLegend());
        }
        View moreLegend = card.findViewById(R.id.moreLegendButton);
        if (moreLegend != null) {
            moreLegend.setOnClickListener(v -> showScoreLegend());
        }
        if (clearFiltersButton != null) {
            clearFiltersButton.setOnClickListener(v -> {
                if (onClearFilters != null) {
                    onClearFilters.run();
                }
            });
        }
        if (organicSwitch != null) {
            organicSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (updatingSwitch) return;
                preferOrganic = isChecked;
                AnalyticsTracker.preferOrganicToggled(isChecked);
                updateOrganicSwitchUi();
                rerank();
            });
        }
    }

    /** Action for the empty-state "Clear all filters" button. */
    public void setOnClearFilters(Runnable onClearFilters) {
        this.onClearFilters = onClearFilters;
    }

    /** Action for a smart "Uncheck <filter>" suggestion in the empty state. */
    public void setOnUncheckFilter(OnUncheckFilterListener onUncheckFilter) {
        this.onUncheckFilter = onUncheckFilter;
    }

    /**
     * The full scanned clean pool (pre-rank), the query tokens used for the
     * exact-match hard filter, and whether this is a non-branded (category)
     * search. For category searches the exact-match filter is strict (ALL
     * query tokens must appear); for branded searches it is lenient (any
     * token). The toggle re-ranks from these stacks.
     */
    public void setPool(List<ProductResult> pool, Set<String> queryTokens,
                        boolean categoryIntent) {
        this.pool = pool != null ? pool : new ArrayList<>();
        this.queryTokens = queryTokens != null ? queryTokens : new HashSet<>();
        this.requireAllTokens = categoryIntent;
        this.expandedKeys.clear();
        this.compareBoxes.clear();
        partitionPool();
    }

    public void setSuperiorTerms(Set<String> superiorTerms) {
        this.superiorTerms = superiorTerms != null ? superiorTerms : new HashSet<>();
    }

    public void setComparePickListener(ComparePickListener listener) {
        this.comparePickListener = listener;
    }

    public void setSaveListener(SaveListener listener) {
        this.saveListener = listener;
    }

    /** Re-style every compare button from the host's pick set (call after any toggle). */
    public void syncCompareBoxes() {
        if (comparePickListener == null) return;
        syncingCompareBoxes = true;
        try {
            for (Map.Entry<String, android.widget.Button> e : compareBoxes.entrySet()) {
                // Key is rowKey(product); find the product via the button tag.
                ProductResult p = (ProductResult) e.getValue().getTag();
                styleCompareButton(e.getValue(), p != null
                        && comparePickListener.isSelectedForCompare(p));
            }
        } finally {
            syncingCompareBoxes = false;
        }
    }

    /** Rounded-rectangle background for action buttons. strokeColor 0 = no stroke. */
    private android.graphics.drawable.GradientDrawable pillBackground(
            int fillColor, int strokeColor) {
        android.graphics.drawable.GradientDrawable d =
                new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(8));
        d.setColor(fillColor);
        if (strokeColor != 0) d.setStroke(dp(1), strokeColor);
        return d;
    }

    /** Real button (not text): fixed styling, no ALL_CAPS, comfortable touch target. */
    private android.widget.Button makeActionButton(Context ctx, String text,
                                                   int fillColor, int textColor,
                                                   int strokeColor) {
        android.widget.Button b = new android.widget.Button(ctx);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13f);
        b.setTypeface(b.getTypeface(), Typeface.BOLD);
        b.setTextColor(textColor);
        b.setBackground(pillBackground(fillColor, strokeColor));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMarginEnd(dp(8));
        b.setLayoutParams(p);
        b.setPadding(dp(16), dp(6), dp(16), dp(6));
        return b;
    }

    /** Compare toggle visuals: green filled when selected, outlined when not. */
    private void styleCompareButton(android.widget.Button b, boolean selected) {
        if (selected) {
            b.setBackground(pillBackground(Color.parseColor("#15803D"), 0));
            b.setTextColor(Color.parseColor("#FFFFFF"));
            b.setText("\u2713 Compare");
        } else {
            b.setBackground(pillBackground(Color.parseColor("#FFFFFF"),
                    Color.parseColor("#9CA3AF")));
            b.setTextColor(Color.parseColor("#374151"));
            b.setText("Compare");
        }
    }

    /** Resets the toggle for a fresh search. */
    public void resetToggle() {
        preferOrganic = false;
        updateOrganicSwitchUi();
    }

    public void hide() {
        if (card != null) card.setVisibility(View.GONE);
    }

    /** Heading currently shown on the card (for the sticky results bar). */
    public String getHeading() {
        return currentHeading;
    }

    public int getExactCount() {
        return lastExactCount;
    }

    public int getMoreCount() {
        return lastMoreCount;
    }

    public boolean isCardShowing() {
        return card != null && card.getVisibility() == View.VISIBLE;
    }

    /**
     * Binds the clean-alternates card as two ranked stacks. For category searches
     * the heading names the category ("Clean choices in <Category>"); otherwise it
     * reads "Clean Alternates". When nothing passes, an explicit empty state names
     * the most restrictive filters and offers to relax them individually.
     *
     * @param reliefCounts per-filter counts of scanned candidates each filter is
     *                     blocking (a candidate blocked by several filters counts
     *                     toward each); ranked descending to surface the most
     *                     restrictive filters first.
     */
    public void show(String foodType, boolean categoryIntent, boolean isClean,
                     Set<String> flaggedCategories, int activeFilterCount,
                     Map<String, Integer> reliefCounts) {
        showInternal(foodType, categoryIntent, isClean, flaggedCategories,
                activeFilterCount, reliefCounts, false);
    }

    private void showInternal(String foodType, boolean categoryIntent, boolean isClean,
                              Set<String> flaggedCategories, int activeFilterCount,
                              Map<String, Integer> reliefCounts, boolean isRerank) {
        // Stash the context so the prefer-organic switch can re-rank without a new search.
        currentFoodType = foodType;
        currentCategoryIntent = categoryIntent;
        currentAlternatesClean = isClean;
        currentFlaggedCategories = flaggedCategories != null ? flaggedCategories : new LinkedHashSet<>();
        currentActiveFilterCount = activeFilterCount;
        currentReliefCounts = reliefCounts != null ? reliefCounts : new LinkedHashMap<>();
        clearFilterSuggestionBar();

        List<AlternateRanker.RankedProduct> exactRanked = topRanked(exactPool);
        List<AlternateRanker.RankedProduct> moreRanked = topRanked(morePool);
        lastExactCount = exactRanked.size();
        lastMoreCount = moreRanked.size();

        if (card != null && title != null && text != null) {
            currentHeading = (categoryIntent && !TextUtils.isEmpty(foodType))
                    ? "Clean choices in " + foodType
                    : "Clean Alternates";
            if (!exactRanked.isEmpty() || !moreRanked.isEmpty()) {
                card.setVisibility(View.VISIBLE);
                title.setText(currentHeading);
                text.setVisibility(View.GONE);
                if (clearFiltersButton != null) {
                    clearFiltersButton.setVisibility(View.GONE);
                }

                bindStack(exactPanel, exactTitle, exactList, "Exact matches",
                        exactRanked);
                bindStack(morePanel, moreTitle, moreList, "More clean options",
                        moreRanked);

                if (!isRerank) {
                    AnalyticsTracker.alternatesViewed(
                            exactRanked.size(), moreRanked.size(), preferOrganic);
                }

                // The prefer-organic switch appears when any clean alternative in the
                // full scanned pool has an organic ingredient — not just the displayed
                // ones — so organic versions stay discoverable even when they don't
                // crack the default ranking. Flipping it re-ranks both stacks,
                // bringing the organic picks to the top.
                boolean anyOrganic = false;
                for (ProductResult p : pool) {
                    if (p != null && AlternateRanker.hasOrganicIngredient(p)) {
                        anyOrganic = true;
                        break;
                    }
                }
                if (organicRow != null) {
                    organicRow.setVisibility(anyOrganic ? View.VISIBLE : View.GONE);
                }
            } else {
                if (organicRow != null) {
                    organicRow.setVisibility(View.GONE);
                }
                hideStackPanels();
                // Dirty product with no clean options, or a category search with no
                // clean choices found: say so explicitly instead of hiding the card.
                if (!isClean || categoryIntent) {
                    card.setVisibility(View.VISIBLE);
                    title.setText(currentHeading);
                    text.setVisibility(View.VISIBLE);
                    if (categoryIntent && !TextUtils.isEmpty(foodType)) {
                        if (activeFilterCount > 0) {
                            showSmartFilterRelief();
                        } else if (flaggedCategories != null && !flaggedCategories.isEmpty()) {
                            text.setText("All items in " + foodType
                                    + " have the following flagged categories: "
                                    + TextUtils.join(", ", flaggedCategories)
                                    + ".\nConsider relaxing your search.");
                        } else {
                            text.setText("No clean choices were found for " + foodType
                                    + " in the database.");
                        }
                    } else if (activeFilterCount > 0) {
                        // Branded search with filters on: same smart relief.
                        text.setText("No clean alternatives for this item"
                                + " with your current filters on.");
                        showSmartFilterRelief();
                    } else {
                        text.setText("No clean alternatives were found for this item"
                                + " in the database.");
                        clearFilterSuggestionBar();
                        if (clearFiltersButton != null) {
                            clearFiltersButton.setVisibility(View.GONE);
                        }
                    }
                } else {
                    // If the product itself is clean, hide the alternates card
                    card.setVisibility(View.GONE);
                }
            }
        }
    }

    /**
     * Re-ranks both stacks with the prefer-organic switch state
     * and re-binds the alternates card. No network request needed.
     */
    private void rerank() {
        showInternal(currentFoodType, currentCategoryIntent, currentAlternatesClean,
                currentFlaggedCategories, currentActiveFilterCount,
                currentReliefCounts, true);
    }

    /** Splits the pool into the exact-match stack and the rest. */
    private void partitionPool() {
        exactPool = new ArrayList<>();
        morePool = new ArrayList<>();
        for (ProductResult p : pool) {
            if (isExactMatch(p)) {
                exactPool.add(p);
            } else {
                morePool.add(p);
            }
        }
    }

    /**
     * Hard filter on the product description.
     * <ul>
     *   <li>Non-branded (category) search: the description must contain ALL
     *       query tokens, in any order.</li>
     *   <li>Branded search: the description must share at least one query token.</li>
     * </ul>
     * Tokens shorter than 3 chars are ignored to avoid noise like "c". With no
     * qualifying tokens at all, nothing counts as an exact match.
     */
    private boolean isExactMatch(ProductResult p) {
        if (p == null || p.name == null) return false;
        Set<String> descTokens = StringNormalizer.wordTokens(p.name);
        boolean anyValidToken = false;
        for (String t : queryTokens) {
            if (t.length() < 3) continue;
            anyValidToken = true;
            boolean present = descTokens.contains(t);
            if (requireAllTokens) {
                if (!present) return false;
            } else if (present) {
                return true;
            }
        }
        return requireAllTokens && anyValidToken;
    }

    /** Ranks one stack with the shared ranking logic (no item cap — the stack scrolls). */
    private List<AlternateRanker.RankedProduct> topRanked(List<ProductResult> stack) {
        return AlternateRanker.rankAndFilter(stack, superiorTerms, preferOrganic);
    }

    /**
     * Smart filter relief for the empty state: instead of "clear ALL filters",
     * rank filters by how many items each is blocking and offer one-tap
     * per-filter relief, most restrictive first.
     */
    private void showSmartFilterRelief() {
        clearFilterSuggestionBar();
        List<Map.Entry<String, Integer>> top = topReliefFilters(3);
        if (!top.isEmpty() && onUncheckFilter != null) {
            Map.Entry<String, Integer> first = top.get(0);
            int n = first.getValue();
            text.setText("No clean options with your current filters on.\n\n\""
                    + first.getKey() + "\" is blocking " + n
                    + (n == 1 ? " item" : " items")
                    + " \u2014 more than any other filter. "
                    + "Uncheck it below to widen your options.");
            buildFilterSuggestionBar(top);
            if (clearFiltersButton != null) clearFiltersButton.setVisibility(View.GONE);
        } else {
            // No per-filter data: keep the old clear-all escape hatch.
            text.setText("No clean options with your current filters on."
                    + "\nTry clearing all filters below.");
            if (clearFiltersButton != null) clearFiltersButton.setVisibility(View.VISIBLE);
        }
    }

    /** Top-N blocking filters by single-filter block count, descending. */
    private List<Map.Entry<String, Integer>> topReliefFilters(int n) {
        List<Map.Entry<String, Integer>> entries =
                new ArrayList<>(currentReliefCounts.entrySet());
        Collections.sort(entries,
                (a, b) -> Integer.compare(b.getValue(), a.getValue()));
        return entries.subList(0, Math.min(n, entries.size()));
    }

    /** One-tap "Uncheck <filter> (N)" buttons appended to the card. */
    private void buildFilterSuggestionBar(List<Map.Entry<String, Integer>> top) {
        if (!(card instanceof ViewGroup)) return;
        Context ctx = card.getContext();
        filterSuggestionBar = new LinearLayout(ctx);
        filterSuggestionBar.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barParams.topMargin = dp(8);
        filterSuggestionBar.setLayoutParams(barParams);
        for (Map.Entry<String, Integer> e : top) {
            final String category = e.getKey();
            final int count = e.getValue();
            Button b = makeActionButton(ctx,
                    "Uncheck " + category + " (" + count + ")",
                    Color.parseColor("#FFFFFF"), Color.parseColor("#2563EB"),
                    Color.parseColor("#2563EB"));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            p.topMargin = dp(6);
            b.setLayoutParams(p);
            b.setOnClickListener(v -> {
                AnalyticsTracker.filterReliefTapped(category, count);
                if (onUncheckFilter != null) onUncheckFilter.onUncheckFilter(category);
            });
            filterSuggestionBar.addView(b);
        }
        ((ViewGroup) card).addView(filterSuggestionBar);
    }

    private void clearFilterSuggestionBar() {
        if (filterSuggestionBar != null) {
            ViewGroup parent = (ViewGroup) filterSuggestionBar.getParent();
            if (parent != null) parent.removeView(filterSuggestionBar);
            filterSuggestionBar = null;
        }
    }

    private void hideStackPanels() {
        if (exactPanel != null) exactPanel.setVisibility(View.GONE);
        if (morePanel != null) morePanel.setVisibility(View.GONE);
    }

    private void bindStack(View panel, TextView panelTitle, LinearLayout list,
                           String heading, List<AlternateRanker.RankedProduct> ranked) {
        if (panel == null) return;
        if (ranked.isEmpty()) {
            panel.setVisibility(View.GONE);
            return;
        }
        panel.setVisibility(View.VISIBLE);
        if (panelTitle != null) {
            panelTitle.setText(heading + " (" + ranked.size() + ")");
        }
        if (list != null) {
            list.removeAllViews();
            for (int i = 0; i < ranked.size(); i++) {
                list.addView(buildRowView(ranked.get(i)));
                if (i < ranked.size() - 1) {
                    list.addView(buildDivider());
                }
            }
        }
    }

    private View buildDivider() {
        View divider = new View(activity);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        divider.setBackgroundColor(Color.parseColor("#E5E7EB"));
        return divider;
    }

    /**
     * One tappable result row: bold name / gray brand / clean-summary meta line,
     * expanding inline to the full ingredient list (superior ingredients
     * highlighted) plus a Buy link.
     */
    private View buildRowView(AlternateRanker.RankedProduct item) {
        ProductResult alt = item.product;
        Context ctx = activity;

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int pad = dp(12);
        row.setPadding(pad, dp(10), pad, dp(10));
        TypedValue tv = new TypedValue();
        ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        row.setBackgroundResource(tv.resourceId);
        row.setClickable(true);
        row.setFocusable(true);

        // Header: icons + stars + name ... chevron
        LinearLayout headerRow = new LinearLayout(ctx);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView titleView = new TextView(ctx);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleView.setLayoutParams(titleParams);
        StringBuilder prefixBuilder = new StringBuilder();
        if (item.hasOrganic) prefixBuilder.append("🌿 ");
        if (item.nonGmo) prefixBuilder.append("🦋 ");
        prefixBuilder.append(item.getStarRating()).append(" ");
        final String titlePrefix = prefixBuilder.toString();
        titleView.setText(titlePrefix
                + StringNormalizer.toTitleCase(cleanProductName(alt.name)));
        titleView.setTextSize(15f);
        titleView.setTypeface(titleView.getTypeface(), Typeface.BOLD);
        titleView.setTextColor(Color.parseColor("#111827"));
        headerRow.addView(titleView);

        // Product thumbnail: conditional slot, only visible when OFF has an image.
        // Fixed 56dp so rows never shift; GONE (text-only row) until resolved.
        android.widget.ImageView thumbView = new android.widget.ImageView(ctx);
        int thumbSize = dp(56);
        LinearLayout.LayoutParams thumbParams = new LinearLayout.LayoutParams(
                thumbSize, thumbSize);
        thumbParams.setMarginEnd(dp(10));
        thumbView.setLayoutParams(thumbParams);
        thumbView.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        thumbView.setVisibility(View.GONE);
        headerRow.addView(thumbView, 0);
        String thumbGtin = alt.gtinUpc;
        thumbView.setTag(thumbGtin);

        TextView chevron = new TextView(ctx);
        chevron.setText("›");
        chevron.setTextSize(20f);
        chevron.setTextColor(Color.parseColor("#9CA3AF"));
        chevron.setPadding(dp(8), 0, 0, 0);
        headerRow.addView(chevron);
        row.addView(headerRow);

        // Brand bubble: pill with eye-catching color — brand is the most
        // critical identifier, so it gets prominence, not gray small text.
        final android.widget.TextView brandView;
        if (!TextUtils.isEmpty(alt.brand)) {
            brandView = new android.widget.TextView(ctx);
            brandView.setText(StringNormalizer.toTitleCase(alt.brand));
            brandView.setTextSize(12f);
            brandView.setTypeface(brandView.getTypeface(), Typeface.BOLD);
            brandView.setTextColor(Color.parseColor("#FFFFFF"));
            brandView.setPadding(dp(10), dp(4), dp(10), dp(4));
            android.graphics.drawable.GradientDrawable brandBg =
                    new android.graphics.drawable.GradientDrawable();
            brandBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            brandBg.setCornerRadius(dp(12));
            brandBg.setColor(Color.parseColor("#C2410C"));
            brandView.setBackground(brandBg);
            LinearLayout.LayoutParams brandParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            brandParams.topMargin = dp(6);
            brandView.setLayoutParams(brandParams);
            row.addView(brandView);
        } else {
            brandView = null;
        }

        // Meta line: ✓ Clean • N ingredients • M clean highlights
        TextView metaView = new TextView(ctx);
        StringBuilder meta = new StringBuilder("✓ Clean • ")
                .append(item.ingredientCount).append(" ingredients");
        if (preferOrganic) {
            meta.append(item.getOrganicTag());
        } else if (item.superiorCount > 0) {
            meta.append(" • ").append(item.superiorCount)
                    .append(item.superiorCount == 1 ? " clean highlight" : " clean highlights");
        }
        metaView.setText(meta.toString());
        metaView.setTextSize(13f);
        metaView.setTextColor(Color.parseColor("#374151"));
        metaView.setPadding(0, dp(4), 0, 0);
        row.addView(metaView);

        // Open Food Facts enrichment: thumbnail, friendlier title, cleaner brand.
        // Single lookup; the resolver serves repeat calls from its memory cache.
        final android.widget.ImageView finalThumb = thumbView;
        final TextView finalTitle = titleView;
        final android.widget.TextView finalBrand = brandView;
        final String finalPrefix = titlePrefix;
        final String finalGtin = thumbGtin;
        // FTC disclosure view is created up front so the OFF callback below
        // can re-evaluate it once friendlier naming arrives; added to the
        // row after the action row further down.
        final TextView disclosureView = new TextView(ctx);
        disclosureView.setText(
                com.barelabel.app.affiliate.AffiliateConfig.DISCLOSURE_TEXT);
        disclosureView.setTextSize(10f);
        disclosureView.setTextColor(Color.parseColor("#6B7280"));
        disclosureView.setPadding(0, dp(4), 0, 0);
        disclosureView.setVisibility(View.GONE);
        final com.barelabel.app.model.ProductResult finalAlt = alt;
        com.barelabel.app.images.ProductImageResolver.resolve(
                ctx, finalGtin, info -> {
                    if (!java.util.Objects.equals(finalGtin, finalThumb.getTag())) return;
                    if (info == null) return;
                    if (info.hasImage()) {
                        finalThumb.setVisibility(View.VISIBLE);
                        com.bumptech.glide.Glide.with(ctx)
                                .load(info.imageUrl)
                                .centerCrop()
                                .into(finalThumb);
                    }
                    if (info.hasName()) {
                        finalTitle.setText(finalPrefix
                                + StringNormalizer.toTitleCase(info.displayName()));
                    }
                    if (finalBrand != null && !info.brands.isEmpty()) {
                        finalBrand.setText(StringNormalizer.toTitleCase(info.brands));
                    }
                    // Re-evaluate the FTC disclosure with OFF naming: the
                    // display name can change whether the item has affiliate
                    // links. Guarded by the same row-binding check above.
                    disclosureView.setVisibility(
                            com.barelabel.app.affiliate.AffiliateNavigator
                                    .hasAffiliateLinks(ctx, finalAlt, info)
                                    ? View.VISIBLE : View.GONE);
                });

        // Action row: real Buy / Save / Compare buttons below the meta line.
        LinearLayout actionRow = new LinearLayout(ctx);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setPadding(0, dp(10), 0, 0);

        android.widget.Button buyButton = makeActionButton(ctx, "Buy",
                Color.parseColor("#2563EB"), Color.parseColor("#FFFFFF"), 0);
        buyButton.setOnClickListener(v -> {
            AnalyticsTracker.buyTapped("alternate");
            buyListener.onBuy(alt);
        });
        actionRow.addView(buyButton);

        if (saveListener != null) {
            android.widget.Button saveButton = makeActionButton(ctx, "Save",
                    Color.parseColor("#FFFFFF"), Color.parseColor("#2563EB"),
                    Color.parseColor("#2563EB"));
            saveButton.setOnClickListener(v -> {
                if (saveListener.onSaveProduct(alt)) {
                    saveButton.setText("Saved \u2713");
                    saveButton.setEnabled(false);
                    saveButton.setBackground(
                            pillBackground(Color.parseColor("#F3F4F6"), 0));
                    saveButton.setTextColor(Color.parseColor("#9CA3AF"));
                }
            });
            actionRow.addView(saveButton);
        }

        if (comparePickListener != null) {
            android.widget.Button compareButton = makeActionButton(ctx, "Compare",
                    Color.parseColor("#FFFFFF"), Color.parseColor("#374151"),
                    Color.parseColor("#9CA3AF"));
            compareButton.setTag(alt);
            compareButton.setOnClickListener(v -> {
                if (syncingCompareBoxes) return;
                // Intended new state (checkbox semantics): toggle of current truth.
                boolean intended = !comparePickListener.isSelectedForCompare(alt);
                AnalyticsTracker.compareCheckboxToggled(intended, "alternate");
                comparePickListener.onToggleComparePick(alt);
                // Host may have rejected the pick (max 2): re-sync to truth.
                syncCompareBoxes();
            });
            actionRow.addView(compareButton);
            compareBoxes.put(rowKey(alt), compareButton);
            styleCompareButton(compareButton,
                    comparePickListener.isSelectedForCompare(alt));
        }

        row.addView(actionRow);

        // FTC affiliate disclosure: only when this item has curated affiliate
        // links, not for plain search-fallback Buy links.
        disclosureView.setVisibility(
                com.barelabel.app.affiliate.AffiliateNavigator.hasAffiliateLinks(
                        ctx, alt, null) ? View.VISIBLE : View.GONE);
        row.addView(disclosureView);

        // Expandable detail: full ingredients + Buy
        LinearLayout expandBox = new LinearLayout(ctx);
        expandBox.setOrientation(LinearLayout.VERTICAL);
        expandBox.setPadding(0, dp(8), 0, 0);

        TextView ingredientsView = new TextView(ctx);
        ingredientsView.setText(buildIngredientsSpannable(
                TextUtils.isEmpty(alt.ingredients) ? "No ingredient list available."
                        : alt.ingredients));
        ingredientsView.setTextSize(13f);
        ingredientsView.setTextColor(Color.parseColor("#374151"));
        ingredientsView.setLineSpacing(dp(2), 1f);
        expandBox.addView(ingredientsView);

        String key = rowKey(alt);
        boolean expanded = expandedKeys.contains(key);
        expandBox.setVisibility(expanded ? View.VISIBLE : View.GONE);
        chevron.setText(expanded ? "⌄" : "›");
        row.addView(expandBox);

        row.setOnClickListener(v -> {
            boolean nowExpanded = expandBox.getVisibility() != View.VISIBLE;
            if (nowExpanded) {
                expandedKeys.add(key);
                AnalyticsTracker.alternateIngredientsViewed();
            } else {
                expandedKeys.remove(key);
            }
            expandBox.setVisibility(nowExpanded ? View.VISIBLE : View.GONE);
            chevron.setText(nowExpanded ? "⌄" : "›");
        });

        return row;
    }

    private String rowKey(ProductResult p) {
        return (p.name == null ? "" : p.name) + "|" + (p.brand == null ? "" : p.brand);
    }

    /**
     * Collapses redundant comma-separated name parts: a part whose words are all
     * contained in an earlier part adds no information (e.g.
     * "MILK CHOCOLATE, CHOCOLATE" → "MILK CHOCOLATE").
     */
    private String cleanProductName(String name) {
        if (TextUtils.isEmpty(name)) return "";
        String[] parts = name.split(",");
        List<String> kept = new ArrayList<>();
        for (String part : parts) {
            Set<String> tokens = StringNormalizer.wordTokens(part);
            if (tokens.isEmpty()) continue;
            boolean redundant = false;
            for (String k : kept) {
                if (StringNormalizer.wordTokens(k).containsAll(tokens)) {
                    redundant = true;
                    break;
                }
            }
            if (!redundant) kept.add(part.trim());
        }
        return kept.isEmpty() ? name.trim() : TextUtils.join(", ", kept);
    }

    /** Ingredient list with superior ("clean highlight") ingredients in bold green. */
    private SpannableStringBuilder buildIngredientsSpannable(String rawIngredients) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        String[] tokens = rawIngredients.split(",");
        for (int i = 0; i < tokens.length; i++) {
            String trimmed = tokens[i].trim();
            if (i > 0) out.append(", ");
            int start = out.length();
            out.append(trimmed);
            int end = out.length();
            if (FlaggedIngredientManager.isSuperiorIngredient(activity, trimmed)) {
                out.setSpan(new android.text.style.ForegroundColorSpan(
                                Color.parseColor("#15803D")),
                        start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new android.text.style.StyleSpan(Typeface.BOLD),
                        start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return out;
    }

    private void updateOrganicSwitchUi() {
        if (organicSwitch == null) return;
        updatingSwitch = true;
        organicSwitch.setChecked(preferOrganic);
        updatingSwitch = false;
        if (organicStateText != null) {
            organicStateText.setText(preferOrganic ? "ON" : "OFF");
            organicStateText.setTextColor(Color.parseColor(
                    preferOrganic ? "#166534" : "#6B7280"));
        }
    }

    /** Explains what the star ratings mean, matching AlternateRanker's logic. */
    private void showScoreLegend() {
        String message = "★ Star ratings\n\n"
                + "★★★ — contains superior ingredients (clean highlights)\n"
                + "★★☆ — among the top-ranked clean picks\n"
                + "★☆☆ — clean, ranked lower\n\n"
                + "With Prefer Organic ON, stars track organic tiers instead:\n"
                + "★★★ — contains an organic ingredient\n"
                + "★★☆ — non-GMO\n"
                + "★☆☆ — neither\n\n"
                + "Ranking prefers more clean highlights, then fewer ingredients.";
        new AlertDialog.Builder(activity)
                .setTitle("How we score")
                .setMessage(message)
                .setPositiveButton("Got it", null)
                .show();
    }

    private int dp(int dps) {
        float density = activity.getResources().getDisplayMetrics().density;
        return Math.round(dps * density);
    }
}
