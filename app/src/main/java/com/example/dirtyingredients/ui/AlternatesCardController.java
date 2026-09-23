package com.example.dirtyingredients.ui;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.dirtyingredients.AlternateRanker;
import com.example.dirtyingredients.FlaggedIngredientManager;
import com.example.dirtyingredients.model.ProductResult;
import com.example.dirtyingredients.util.StringNormalizer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Owns the "clean alternates" card, rendered as two stacks:
 * <ul>
 *   <li><b>Exact matches</b> (green panel) — clean products whose description
 *       shares at least one query token (hard filter).</li>
 *   <li><b>More clean options</b> (blue panel) — the remaining clean products
 *       from the scanned pool.</li>
 * </ul>
 * Each stack is ranked with the same AlternateRanker logic. The prefer-organic
 * toggle re-ranks both stacks locally — no new network request.
 */
public class AlternatesCardController {

    private static final int MAX_PER_STACK = 5;

    /** Called when the user taps BUY! in an alternate's ingredient dialog. */
    public interface BuyListener {
        void onBuy(String productName);
    }

    private final AppCompatActivity activity;
    private final View card;
    private final TextView title;
    private final TextView text;
    private final View exactPanel;
    private final TextView exactTitle;
    private final TextView exactText;
    private final View morePanel;
    private final TextView moreTitle;
    private final TextView moreText;
    private final Button organicButton;
    private final BuyListener buyListener;

    private boolean preferOrganic = false;
    private List<ProductResult> pool = new ArrayList<>();
    private List<ProductResult> exactPool = new ArrayList<>();
    private List<ProductResult> morePool = new ArrayList<>();
    private Set<String> queryTokens = new HashSet<>();
    private Set<String> superiorTerms = new HashSet<>();

    // Last shown context, so the toggle can re-render without a new search.
    private String currentFoodType = "";
    private boolean currentCategoryIntent = false;
    private boolean currentAlternatesClean = true;
    private Set<String> currentFlaggedCategories = new LinkedHashSet<>();

    public AlternatesCardController(AppCompatActivity activity, View card, TextView title,
                                    TextView text, Button organicButton, BuyListener buyListener) {
        this.activity = activity;
        this.card = card;
        this.title = title;
        this.text = text;
        this.organicButton = organicButton;
        this.buyListener = buyListener;

        this.exactPanel = card.findViewById(com.example.dirtyingredients.R.id.exactMatchesPanel);
        this.exactTitle = card.findViewById(com.example.dirtyingredients.R.id.exactMatchesTitle);
        this.exactText = card.findViewById(com.example.dirtyingredients.R.id.exactMatchesText);
        this.morePanel = card.findViewById(com.example.dirtyingredients.R.id.moreOptionsPanel);
        this.moreTitle = card.findViewById(com.example.dirtyingredients.R.id.moreOptionsTitle);
        this.moreText = card.findViewById(com.example.dirtyingredients.R.id.moreOptionsText);

        if (text != null) {
            text.setMovementMethod(LinkMovementMethod.getInstance());
        }
        if (organicButton != null) {
            organicButton.setOnClickListener(v -> {
                preferOrganic = !preferOrganic;
                rerank();
            });
        }
    }

    /**
     * The full scanned clean pool (pre-rank) plus the query tokens used for the
     * exact-match hard filter. The toggle re-ranks from these stacks.
     */
    public void setPool(List<ProductResult> pool, Set<String> queryTokens) {
        this.pool = pool != null ? pool : new ArrayList<>();
        this.queryTokens = queryTokens != null ? queryTokens : new HashSet<>();
        partitionPool();
    }

    public void setSuperiorTerms(Set<String> superiorTerms) {
        this.superiorTerms = superiorTerms != null ? superiorTerms : new HashSet<>();
    }

    /** Resets the toggle for a fresh search. */
    public void resetToggle() {
        preferOrganic = false;
    }

    public void hide() {
        if (card != null) card.setVisibility(View.GONE);
    }

    /**
     * Binds the clean-alternates card as two ranked stacks. For category searches
     * the heading names the category ("Clean choices in <Category>"); otherwise it
     * reads "Clean Alternates". When a category search finds nothing, the blocking
     * flagged categories are listed so the user knows why (e.g. everything contains
     * gluten).
     */
    public void show(String foodType, boolean categoryIntent, boolean isClean,
                     Set<String> flaggedCategories) {
        // Stash the context so the prefer-organic toggle can re-rank without a new search.
        currentFoodType = foodType;
        currentCategoryIntent = categoryIntent;
        currentAlternatesClean = isClean;
        currentFlaggedCategories = flaggedCategories != null ? flaggedCategories : new LinkedHashSet<>();

        List<AlternateRanker.RankedProduct> exactRanked = topRanked(exactPool);
        List<AlternateRanker.RankedProduct> moreRanked = topRanked(morePool);

        if (card != null && title != null && text != null) {
            String alternatesHeading = (categoryIntent && !TextUtils.isEmpty(foodType))
                    ? "Clean choices in " + foodType
                    : "Clean Alternates";
            if (!exactRanked.isEmpty() || !moreRanked.isEmpty()) {
                card.setVisibility(View.VISIBLE);
                title.setText(alternatesHeading);
                text.setVisibility(View.GONE);

                bindStack(exactPanel, exactTitle, exactText, "Exact matches", exactRanked);
                bindStack(morePanel, moreTitle, moreText, "More clean options", moreRanked);

                // The prefer-organic toggle appears when any clean alternative in the
                // full scanned pool has an organic ingredient — not just the displayed
                // ones — so organic versions stay discoverable even when they don't
                // crack the default ranking. Tapping it re-ranks both stacks,
                // bringing the organic picks to the top.
                boolean anyOrganic = false;
                for (ProductResult p : pool) {
                    if (p != null && AlternateRanker.hasOrganicIngredient(p)) {
                        anyOrganic = true;
                        break;
                    }
                }
                if (organicButton != null) {
                    organicButton.setVisibility(anyOrganic ? View.VISIBLE : View.GONE);
                    updateOrganicButton();
                }
            } else {
                if (organicButton != null) {
                    organicButton.setVisibility(View.GONE);
                }
                hideStackPanels();
                // Dirty product with no clean options, or a category search with no
                // clean choices found: say so explicitly instead of hiding the card.
                if (!isClean || categoryIntent) {
                    card.setVisibility(View.VISIBLE);
                    title.setText(alternatesHeading);
                    text.setVisibility(View.VISIBLE);
                    if (categoryIntent && flaggedCategories != null && !flaggedCategories.isEmpty()
                            && !TextUtils.isEmpty(foodType)) {
                        text.setText("All items in " + foodType
                                + " have the following flagged categories: "
                                + TextUtils.join(", ", flaggedCategories)
                                + ".\nConsider relaxing your search.");
                    } else {
                        text.setText(categoryIntent
                                ? "No clean choices were found for this category in the database."
                                : "No clean alternatives were found for this item in the database.");
                    }
                } else {
                    // If the product itself is clean, hide the alternates card
                    card.setVisibility(View.GONE);
                }
            }
        }
    }

    /**
     * Re-ranks both stacks with the prefer-organic toggle state
     * and re-binds the alternates card. No network request needed.
     */
    private void rerank() {
        updateOrganicButton();
        show(currentFoodType, currentCategoryIntent, currentAlternatesClean,
                currentFlaggedCategories);
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
     * Hard filter: the product description shares at least one query token
     * (tokens shorter than 3 chars are ignored to avoid noise like "c").
     */
    private boolean isExactMatch(ProductResult p) {
        if (p == null || p.name == null) return false;
        Set<String> descTokens = StringNormalizer.wordTokens(p.name);
        for (String t : queryTokens) {
            if (t.length() >= 3 && descTokens.contains(t)) {
                return true;
            }
        }
        return false;
    }

    /** Ranks one stack with the shared ranking logic, capped at MAX_PER_STACK. */
    private List<AlternateRanker.RankedProduct> topRanked(List<ProductResult> stack) {
        List<AlternateRanker.RankedProduct> ranked =
                AlternateRanker.rankAndFilter(stack, superiorTerms, preferOrganic);
        if (ranked.size() > MAX_PER_STACK) {
            ranked = ranked.subList(0, MAX_PER_STACK);
        }
        return ranked;
    }

    private void hideStackPanels() {
        if (exactPanel != null) exactPanel.setVisibility(View.GONE);
        if (morePanel != null) morePanel.setVisibility(View.GONE);
    }

    private void bindStack(View panel, TextView panelTitle, TextView panelText,
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
        if (panelText != null) {
            panelText.setText(buildItemsSpannable(ranked));
            panelText.setMovementMethod(LinkMovementMethod.getInstance());
        }
    }

    private SpannableStringBuilder buildItemsSpannable(List<AlternateRanker.RankedProduct> ranked) {
        SpannableStringBuilder spannableBuilder = new SpannableStringBuilder();

        for (int i = 0; i < ranked.size(); i++) {
            AlternateRanker.RankedProduct item = ranked.get(i);
            ProductResult alt = item.product;

            int startPos = spannableBuilder.length();

            // Little attention-drawing icons: herb for organic picks,
            // butterfly (Non-GMO Project mark) for non-GMO picks.
            String itemHeader = "";
            if (item.hasOrganic) itemHeader += "🌿 "; // U+1F33F
            if (item.nonGmo) itemHeader += "🦋 "; // U+1F98B
            itemHeader += item.getStarRating() + " " + alt.name;
            if (!TextUtils.isEmpty(alt.brand)) {
                itemHeader += " (" + alt.brand + ")";
            }
            itemHeader += "\n   ✓ Clean • " + item.ingredientCount + " ingredients";
            if (preferOrganic) {
                itemHeader += item.getOrganicTag();
            } else if (item.superiorCount > 0) {
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

        return spannableBuilder;
    }

    private void updateOrganicButton() {
        if (organicButton == null) return;
        organicButton.setText(preferOrganic ? "✓ Prefer Organic" : "🌿 Prefer Organic");
    }

    private void showAlternateIngredientsDialog(ProductResult altProduct) {
        if (altProduct == null) return;

        String titleText = altProduct.name;
        if (!TextUtils.isEmpty(altProduct.brand)) {
            titleText += " (" + altProduct.brand + ")";
        }

        SpannableStringBuilder dialogContent = new SpannableStringBuilder();

        // 1. Add Superior Badge Explanatory Note
        String explanation = "Superior badges are provided when the item has one or more superior ingredients\n\n";
        int expStart = dialogContent.length();
        dialogContent.append(explanation);

        dialogContent.setSpan(new android.text.style.StyleSpan(Typeface.ITALIC),
                expStart, dialogContent.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        dialogContent.setSpan(new android.text.style.ForegroundColorSpan(Color.parseColor("#4B5563")),
                expStart, dialogContent.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        // 2. Ingredients Header
        int headerStart = dialogContent.length();
        dialogContent.append("INGREDIENTS:\n\n");
        dialogContent.setSpan(new android.text.style.StyleSpan(Typeface.BOLD),
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
            if (FlaggedIngredientManager.isSuperiorIngredient(activity, trimmedToken)) {
                dialogContent.setSpan(new android.text.style.ForegroundColorSpan(Color.parseColor("#15803D")),
                        tokenStart, tokenEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                dialogContent.setSpan(new android.text.style.StyleSpan(Typeface.BOLD),
                        tokenStart, tokenEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        // Build TextView to support Spannable formatting
        TextView messageView = new TextView(activity);
        messageView.setText(dialogContent);
        messageView.setTextSize(15f);
        messageView.setPadding(48, 32, 48, 16);
        messageView.setLineSpacing(1.2f, 1.1f);

        new AlertDialog.Builder(activity)
                .setTitle(titleText)
                .setView(messageView)
                .setPositiveButton("Close", null)
                .setNeutralButton("BUY!", (dialog, which) -> buyListener.onBuy(altProduct.name))
                .show();
    }
}
