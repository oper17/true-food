package com.barelabel.app.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.barelabel.app.FlaggedIngredientManager;
import com.barelabel.app.R;
import com.barelabel.app.model.ScannedProduct;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builds the side-by-side ingredient comparison table for two products
 * into a container. Each ingredient row shows one symbol per product:
 *
 * <ul>
 *   <li>green check — ingredient present and clean (not flagged)</li>
 *   <li>red X — ingredient present and flagged</li>
 *   <li>leaf — ingredient present and a superior (clean-highlight) ingredient</li>
 *   <li>gray dash-in-circle — ingredient not present in that product at all</li>
 * </ul>
 *
 * Flag verdicts use the flags stored at scan time; superior matching uses
 * {@link FlaggedIngredientManager}. A legend explaining the symbols is
 * rendered at the bottom of the table.
 */
public final class CompareViewBuilder {

    private CompareViewBuilder() {
    }

    private enum Status {
        ABSENT, CLEAN, FLAGGED, SUPERIOR
    }

    /** Buy / Save actions for the two compared products. */
    public interface CompareActionListener {
        void onBuy(ScannedProduct p);
        /** @return true when the product was actually saved. */
        boolean onSave(ScannedProduct p);
    }

    /** Clears the container and renders the full comparison table + legend. */
    public static void buildComparison(Context context, LinearLayout container,
                                       ScannedProduct a, ScannedProduct b,
                                       java.util.Set<String> savedKeys,
                                       CompareActionListener listener) {
        container.removeAllViews();
        if (a == null || b == null) return;

        // Product header columns
        LinearLayout headerRow = new LinearLayout(context);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.addView(productHeader(context, a, savedKeys, listener), columnParams());
        headerRow.addView(productHeader(context, b, savedKeys, listener), columnParams());
        container.addView(headerRow);

        container.addView(divider(context), dividerParams(context));

        // Ingredient rows: A's ingredients in order, then B-only ingredients.
        Set<String> aKeys = ingredientKeys(a.ingredients);
        Set<String> bKeys = ingredientKeys(b.ingredients);
        List<String> union = unionIngredients(a.ingredients, b.ingredients);
        for (String ingredient : union) {
            container.addView(ingredientRow(context, ingredient,
                    statusFor(context, ingredient, a, aKeys),
                    statusFor(context, ingredient, b, bKeys)));
        }

        container.addView(divider(context), dividerParams(context));
        container.addView(legendView(context));
    }

    private static LinearLayout.LayoutParams columnParams() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private static View divider(Context context) {
        View divider = new View(context);
        divider.setBackgroundColor(Color.parseColor("#E5E7EB"));
        return divider;
    }

    private static LinearLayout.LayoutParams dividerParams(Context context) {
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1));
        divParams.topMargin = dp(context, 12);
        divParams.bottomMargin = dp(context, 4);
        return divParams;
    }

    /** Name + verdict pill + flagged count + Buy/Save actions, centered in its column. */
    private static LinearLayout productHeader(Context context, ScannedProduct p,
                                              java.util.Set<String> savedKeys,
                                              CompareActionListener listener) {
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(context, 4), 0, dp(context, 4), 0);

        TextView name = new TextView(context);
        name.setText(p.displayName());
        name.setTextSize(15f);
        name.setTypeface(null, Typeface.BOLD);
        name.setTextColor(Color.parseColor("#111827"));
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(3);
        name.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(name);

        TextView pill = new TextView(context);
        boolean clean = p.clean;
        pill.setText(clean ? "\u2713 CLEAN" : "\u26A0 DIRTY");
        pill.setTextSize(12f);
        pill.setTypeface(null, Typeface.BOLD);
        pill.setTextColor(Color.parseColor(clean ? "#166534" : "#991B1B"));
        pill.setBackgroundResource(clean ? R.drawable.chip_clean_background
                : R.drawable.chip_dirty_background);
        pill.setPadding(dp(context, 10), dp(context, 4), dp(context, 10), dp(context, 4));
        LinearLayout.LayoutParams pillParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pillParams.topMargin = dp(context, 6);
        col.addView(pill, pillParams);

        TextView count = new TextView(context);
        int n = p.flagged == null ? 0 : p.flagged.size();
        count.setText(n == 0 ? "No flagged ingredients" : n + " flagged");
        count.setTextSize(12f);
        count.setTextColor(Color.parseColor("#6B7280"));
        count.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams countParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        countParams.topMargin = dp(context, 4);
        col.addView(count, countParams);

        if (listener != null) {
            col.addView(actionRow(context, p, savedKeys, listener));

            // FTC affiliate disclosure: only when this item has curated
            // affiliate links, not for plain search-fallback Buy links.
            if (com.barelabel.app.affiliate.AffiliateNavigator.hasAffiliateLinks(
                    context, p.name, p.brand)) {
                TextView disclosure = new TextView(context);
                disclosure.setText(
                        com.barelabel.app.affiliate.AffiliateConfig.DISCLOSURE_TEXT);
                disclosure.setTextSize(10f);
                disclosure.setTextColor(Color.parseColor("#6B7280"));
                disclosure.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams disclosureParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                disclosureParams.topMargin = dp(context, 4);
                col.addView(disclosure, disclosureParams);
            }
        }

        return col;
    }

    /** Buy + Save text buttons under a product column. */
    private static LinearLayout actionRow(Context context, ScannedProduct p,
                                          java.util.Set<String> savedKeys,
                                          CompareActionListener listener) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(context, 8);
        row.setLayoutParams(rowParams);

        TextView buy = new TextView(context);
        buy.setText("Buy");
        buy.setTextSize(14f);
        buy.setTypeface(null, Typeface.BOLD);
        buy.setTextColor(Color.parseColor("#2563EB"));
        buy.setPadding(dp(context, 12), dp(context, 6), dp(context, 12), dp(context, 6));
        buy.setClickable(true);
        buy.setFocusable(true);
        buy.setOnClickListener(v -> listener.onBuy(p));
        row.addView(buy);

        TextView save = new TextView(context);
        boolean alreadySaved = savedKeys != null && savedKeys.contains(productKey(p));
        save.setText(alreadySaved ? "Saved \u2713" : "Save");
        save.setTextSize(14f);
        save.setTypeface(null, Typeface.BOLD);
        save.setTextColor(Color.parseColor(alreadySaved ? "#9CA3AF" : "#374151"));
        save.setPadding(dp(context, 12), dp(context, 6), dp(context, 12), dp(context, 6));
        save.setEnabled(!alreadySaved);
        save.setClickable(!alreadySaved);
        save.setFocusable(!alreadySaved);
        if (!alreadySaved) {
            save.setOnClickListener(v -> {
                if (listener.onSave(p)) {
                    save.setText("Saved \u2713");
                    save.setTextColor(Color.parseColor("#9CA3AF"));
                    save.setEnabled(false);
                }
            });
        }
        row.addView(save);

        return row;
    }

    private static String productKey(ScannedProduct p) {
        String name = p.name == null ? "" : p.name.trim().toLowerCase(Locale.US);
        String brand = p.brand == null ? "" : p.brand.trim().toLowerCase(Locale.US);
        return name + "|" + brand;
    }

    /** One row: [status A] ingredient name [status B]. */
    private static LinearLayout ingredientRow(Context context, String ingredient,
                                              Status statusA, Status statusB) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(context, 7), 0, dp(context, 7));

        row.addView(statusView(context, statusA));

        TextView name = new TextView(context);
        name.setText(ingredient);
        name.setTextSize(14f);
        name.setTextColor(Color.parseColor("#1F2937"));
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nameParams.leftMargin = dp(context, 8);
        nameParams.rightMargin = dp(context, 8);
        row.addView(name, nameParams);

        row.addView(statusView(context, statusB));
        return row;
    }

    /** Symbol per product: gray dash-in-circle when absent, else by verdict. */
    private static TextView statusView(Context context, Status status) {
        TextView v = new TextView(context);
        switch (status) {
            case ABSENT:
                v.setText("\u2296"); // dash inside a circle
                v.setTextColor(Color.parseColor("#9CA3AF"));
                break;
            case FLAGGED:
                v.setText("\u2715");
                v.setTextColor(Color.parseColor("#DC2626"));
                break;
            case SUPERIOR:
                v.setText("\uD83C\uDF3F"); // leaf emoji
                v.setTextColor(Color.parseColor("#15803D"));
                break;
            case CLEAN:
            default:
                v.setText("\u2713");
                v.setTextColor(Color.parseColor("#16A34A"));
                break;
        }
        v.setTextSize(status == Status.SUPERIOR ? 16f : 18f);
        v.setTypeface(null, Typeface.BOLD);
        v.setGravity(Gravity.CENTER);
        v.setWidth(dp(context, 32));
        return v;
    }

    private static Status statusFor(Context context, String ingredient,
                                    ScannedProduct p, Set<String> keys) {
        if (!keys.contains(key(ingredient))) return Status.ABSENT;
        if (isFlagged(ingredient, p.flagged)) return Status.FLAGGED;
        if (FlaggedIngredientManager.isSuperiorIngredient(context, ingredient)) {
            return Status.SUPERIOR;
        }
        return Status.CLEAN;
    }

    /** Legend explaining the four symbols, rendered under the table. */
    private static TextView legendView(Context context) {
        TextView legend = new TextView(context);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        appendLegendItem(sb, "\u2713 Clean", "#16A34A");
        sb.append("   ");
        appendLegendItem(sb, "\u2715 Flagged", "#DC2626");
        sb.append("   ");
        appendLegendItem(sb, "\uD83C\uDF3F Superior", "#15803D");
        sb.append("   ");
        appendLegendItem(sb, "\u2296 Not in product", "#9CA3AF");
        legend.setText(sb);
        legend.setTextSize(12f);
        legend.setTextColor(Color.parseColor("#6B7280"));
        legend.setGravity(Gravity.CENTER);
        legend.setPadding(0, dp(context, 4), 0, dp(context, 8));
        return legend;
    }

    private static void appendLegendItem(SpannableStringBuilder sb, String text,
                                         String colorHex) {
        int start = sb.length();
        sb.append(text);
        sb.setSpan(new android.text.style.ForegroundColorSpan(Color.parseColor(colorHex)),
                start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new android.text.style.StyleSpan(Typeface.BOLD),
                start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static String key(String ingredient) {
        return ingredient == null ? "" : ingredient.trim().toLowerCase(Locale.US);
    }

    /** Lowercase keys of one product's own ingredient list. */
    private static Set<String> ingredientKeys(String ingredients) {
        Set<String> keys = new HashSet<>();
        if (ingredients == null) return keys;
        for (String raw : ingredients.split(",")) {
            String t = raw.trim();
            if (!t.isEmpty()) keys.add(key(t));
        }
        return keys;
    }

    /** A's ingredients in order, then ingredients only in B. Case-insensitive dedupe. */
    private static List<String> unionIngredients(String a, String b) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        addTokens(out, seen, a);
        addTokens(out, seen, b);
        return out;
    }

    private static void addTokens(List<String> out, Set<String> seen, String ingredients) {
        if (ingredients == null) return;
        for (String raw : ingredients.split(",")) {
            String t = raw.trim();
            if (t.isEmpty()) continue;
            if (seen.add(key(t))) out.add(t);
        }
    }

    /** An ingredient counts as flagged when any stored flagged term appears in it. */
    private static boolean isFlagged(String ingredient, List<String> flaggedTerms) {
        if (flaggedTerms == null || flaggedTerms.isEmpty()) return false;
        String lower = ingredient.toLowerCase(Locale.US);
        for (String term : flaggedTerms) {
            if (term != null && !term.trim().isEmpty()
                    && lower.contains(term.trim().toLowerCase(Locale.US))) {
                return true;
            }
        }
        return false;
    }

    private static int dp(Context context, int dps) {
        return Math.round(dps * context.getResources().getDisplayMetrics().density);
    }
}
