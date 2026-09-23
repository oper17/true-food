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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Owns the "clean alternates" card: rendering the ranked list, the
 * prefer-organic toggle (state + button), and the per-alternate ingredient
 * dialog. Toggling re-ranks the full scanned pool locally — no new network
 * request.
 */
public class AlternatesCardController {

    /** Called when the user taps BUY! in an alternate's ingredient dialog. */
    public interface BuyListener {
        void onBuy(String productName);
    }

    private final AppCompatActivity activity;
    private final View card;
    private final TextView title;
    private final TextView text;
    private final Button organicButton;
    private final BuyListener buyListener;

    private boolean preferOrganic = false;
    private List<ProductResult> pool = new ArrayList<>();
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

    /** The full scanned clean pool (pre-rank); the toggle re-ranks from this. */
    public void setPool(List<ProductResult> pool) {
        this.pool = pool != null ? pool : new ArrayList<>();
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
     * Binds the clean-alternates card. For category searches the heading names the
     * category ("Clean choices in <Category>"); otherwise it reads "Clean Alternates".
     * When a category search finds nothing, the blocking flagged categories are listed
     * so the user knows why (e.g. everything contains gluten).
     */
    public void show(String foodType, boolean categoryIntent, boolean isClean,
                     List<AlternateRanker.RankedProduct> alternates,
                     Set<String> flaggedCategories) {
        // Stash the context so the prefer-organic toggle can re-rank without a new search.
        currentFoodType = foodType;
        currentCategoryIntent = categoryIntent;
        currentAlternatesClean = isClean;
        currentFlaggedCategories = flaggedCategories != null ? flaggedCategories : new LinkedHashSet<>();

        if (card != null && title != null && text != null) {
            String alternatesHeading = (categoryIntent && !TextUtils.isEmpty(foodType))
                    ? "Clean choices in " + foodType
                    : "Clean Alternates";
            if (alternates != null && !alternates.isEmpty()) {
                card.setVisibility(View.VISIBLE);
                title.setText(alternatesHeading);

                SpannableStringBuilder spannableBuilder = new SpannableStringBuilder();

                for (int i = 0; i < alternates.size(); i++) {
                    AlternateRanker.RankedProduct item = alternates.get(i);
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

                text.setText(spannableBuilder);
                text.setMovementMethod(LinkMovementMethod.getInstance());

                // The prefer-organic toggle appears when any clean alternative in the
                // full scanned pool has an organic ingredient — not just the five
                // displayed — so organic versions stay discoverable even when they
                // don't crack the default top-5 ranking. Tapping it re-ranks the
                // whole pool, bringing the organic picks to the top.
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
                // Dirty product with no clean options, or a category search with no
                // clean choices found: say so explicitly instead of hiding the card.
                if (!isClean || categoryIntent) {
                    card.setVisibility(View.VISIBLE);
                    title.setText(alternatesHeading);
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
     * Re-ranks the full clean pool with the prefer-organic toggle state
     * and re-binds the alternates card. No network request needed.
     */
    private void rerank() {
        List<AlternateRanker.RankedProduct> reranked =
                AlternateRanker.rankAndFilter(pool, superiorTerms, preferOrganic);
        if (reranked.size() > 5) {
            reranked = reranked.subList(0, 5);
        }
        updateOrganicButton();
        show(currentFoodType, currentCategoryIntent, currentAlternatesClean,
                reranked, currentFlaggedCategories);
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
