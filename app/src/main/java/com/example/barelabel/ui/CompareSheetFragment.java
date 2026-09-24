package com.example.barelabel.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;

import com.example.barelabel.R;
import com.example.barelabel.model.ScannedProduct;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Side-by-side ingredient comparison for two history products.
 * Each ingredient row shows a green check (not flagged) or red X (flagged)
 * per product, using the flags stored at scan time.
 */
public class CompareSheetFragment extends BottomSheetDialogFragment {

    private static final String ARG_A = "product_a";
    private static final String ARG_B = "product_b";

    public static CompareSheetFragment newInstance(ScannedProduct a, ScannedProduct b) {
        CompareSheetFragment f = new CompareSheetFragment();
        Bundle args = new Bundle();
        args.putString(ARG_A, a.toJson().toString());
        args.putString(ARG_B, b.toJson().toString());
        f.setArguments(args);
        return f;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        Context context = requireContext();
        ScannedProduct a = productFromArgs(ARG_A);
        ScannedProduct b = productFromArgs(ARG_B);
        if (a == null || b == null) {
            dismiss();
            return new View(context);
        }

        NestedScrollView scroll = new NestedScrollView(context);
        scroll.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, dp(12), pad, pad);
        scroll.addView(root);

        // Drag handle
        View handle = new View(context);
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setShape(GradientDrawable.RECTANGLE);
        handleBg.setColor(Color.parseColor("#D1D5DB"));
        handleBg.setCornerRadius(dp(2));
        handle.setBackground(handleBg);
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(40), dp(4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.bottomMargin = dp(12);
        root.addView(handle, handleParams);

        // Title
        TextView title = new TextView(context);
        title.setText("Compare products");
        title.setTextSize(20f);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(Color.parseColor("#111827"));
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.bottomMargin = dp(12);
        root.addView(title, titleParams);

        // Product header columns
        LinearLayout headerRow = new LinearLayout(context);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.addView(productHeader(context, a), columnParams());
        headerRow.addView(productHeader(context, b), columnParams());
        root.addView(headerRow);

        // Divider
        View divider = new View(context);
        divider.setBackgroundColor(Color.parseColor("#E5E7EB"));
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        divParams.topMargin = dp(12);
        divParams.bottomMargin = dp(4);
        root.addView(divider, divParams);

        // Ingredient rows: A's ingredients in order, then B-only ingredients.
        List<String> union = unionIngredients(a.ingredients, b.ingredients);
        for (String ingredient : union) {
            root.addView(ingredientRow(context, ingredient,
                    isFlagged(ingredient, a.flagged), isFlagged(ingredient, b.flagged)));
        }

        // Close
        Button close = new Button(context);
        close.setText("Close");
        close.setOnClickListener(v -> dismiss());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        closeParams.topMargin = dp(16);
        root.addView(close, closeParams);

        return scroll;
    }

    private ScannedProduct productFromArgs(String key) {
        Bundle args = getArguments();
        if (args == null) return null;
        try {
            return ScannedProduct.fromJson(new JSONObject(args.getString(key, "{}")));
        } catch (Exception e) {
            return null;
        }
    }

    private LinearLayout.LayoutParams columnParams() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    /** Name + verdict pill + flagged count, centered in its column. */
    private LinearLayout productHeader(Context context, ScannedProduct p) {
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(4), 0, dp(4), 0);

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
        pill.setPadding(dp(10), dp(4), dp(10), dp(4));
        LinearLayout.LayoutParams pillParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pillParams.topMargin = dp(6);
        col.addView(pill, pillParams);

        TextView count = new TextView(context);
        int n = p.flagged == null ? 0 : p.flagged.size();
        count.setText(n == 0 ? "No flagged ingredients" : n + " flagged");
        count.setTextSize(12f);
        count.setTextColor(Color.parseColor("#6B7280"));
        count.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams countParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        countParams.topMargin = dp(4);
        col.addView(count, countParams);

        return col;
    }

    /** One row: [status A] ingredient name [status B]. */
    private LinearLayout ingredientRow(Context context, String ingredient,
                                       boolean flaggedA, boolean flaggedB) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));

        row.addView(statusView(context, flaggedA));

        TextView name = new TextView(context);
        name.setText(ingredient);
        name.setTextSize(14f);
        name.setTextColor(Color.parseColor("#1F2937"));
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nameParams.leftMargin = dp(8);
        nameParams.rightMargin = dp(8);
        row.addView(name, nameParams);

        row.addView(statusView(context, flaggedB));
        return row;
    }

    /** Green check when the ingredient is fine, red X when flagged. */
    private TextView statusView(Context context, boolean flagged) {
        TextView v = new TextView(context);
        v.setText(flagged ? "✕" : "✓");
        v.setTextSize(18f);
        v.setTypeface(null, Typeface.BOLD);
        v.setTextColor(Color.parseColor(flagged ? "#DC2626" : "#16A34A"));
        v.setGravity(Gravity.CENTER);
        v.setWidth(dp(32));
        return v;
    }

    /** A's ingredients in order, then ingredients only in B. Case-insensitive dedupe. */
    private List<String> unionIngredients(String a, String b) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        addTokens(out, seen, a);
        addTokens(out, seen, b);
        return out;
    }

    private void addTokens(List<String> out, Set<String> seen, String ingredients) {
        if (ingredients == null) return;
        for (String raw : ingredients.split(",")) {
            String t = raw.trim();
            if (t.isEmpty()) continue;
            String key = t.toLowerCase(Locale.US);
            if (seen.add(key)) out.add(t);
        }
    }

    /** An ingredient counts as flagged when any stored flagged term appears in it. */
    private boolean isFlagged(String ingredient, List<String> flaggedTerms) {
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

    private int dp(int dps) {
        return Math.round(dps * requireContext().getResources().getDisplayMetrics().density);
    }
}
