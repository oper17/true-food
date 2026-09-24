package com.example.barelabel.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.example.barelabel.R;
import com.example.barelabel.model.ScannedProduct;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builds the side-by-side ingredient comparison view for two history products.
 * Shared by the Compare tab and the (legacy) compare bottom sheet: each
 * ingredient row shows a green check (not flagged) or red X (flagged) per
 * product, using the flags stored at scan time.
 */
public final class CompareViewBuilder {

    private CompareViewBuilder() {}

    /** Full comparison column: product headers, divider, ingredient rows. */
    public static LinearLayout build(Context context, ScannedProduct a, ScannedProduct b) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);

        // Product header columns
        LinearLayout headerRow = new LinearLayout(context);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.addView(productHeader(context, a), columnParams(context));
        headerRow.addView(productHeader(context, b), columnParams(context));
        root.addView(headerRow);

        // Divider
        View divider = new View(context);
        divider.setBackgroundColor(Color.parseColor("#E5E7EB"));
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1));
        divParams.topMargin = dp(context, 12);
        divParams.bottomMargin = dp(context, 4);
        root.addView(divider, divParams);

        // Ingredient rows: A's ingredients in order, then B-only ingredients.
        List<String> union = unionIngredients(a.ingredients, b.ingredients);
        for (String ingredient : union) {
            root.addView(ingredientRow(context, ingredient,
                    isFlagged(ingredient, a.flagged), isFlagged(ingredient, b.flagged)));
        }
        return root;
    }

    private static LinearLayout.LayoutParams columnParams(Context context) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    /** Name + verdict pill + flagged count, centered in its column. */
    private static LinearLayout productHeader(Context context, ScannedProduct p) {
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
        pill.setText(clean ? "✓ CLEAN" : "⚠ DIRTY");
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

        return col;
    }

    /** One row: [status A] ingredient name [status B]. */
    private static LinearLayout ingredientRow(Context context, String ingredient,
                                              boolean flaggedA, boolean flaggedB) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(context, 7), 0, dp(context, 7));

        row.addView(statusView(context, flaggedA));

        TextView name = new TextView(context);
        name.setText(ingredient);
        name.setTextSize(14f);
        name.setTextColor(Color.parseColor("#1F2937"));
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nameParams.leftMargin = dp(context, 8);
        nameParams.rightMargin = dp(context, 8);
        row.addView(name, nameParams);

        row.addView(statusView(context, flaggedB));
        return row;
    }

    /** Green check when the ingredient is fine, red X when flagged. */
    private static TextView statusView(Context context, boolean flagged) {
        TextView v = new TextView(context);
        v.setText(flagged ? "✕" : "✓");
        v.setTextSize(18f);
        v.setTypeface(null, Typeface.BOLD);
        v.setTextColor(Color.parseColor(flagged ? "#DC2626" : "#16A34A"));
        v.setGravity(Gravity.CENTER);
        v.setWidth(dp(context, 32));
        return v;
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
            String key = t.toLowerCase(Locale.US);
            if (seen.add(key)) out.add(t);
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
