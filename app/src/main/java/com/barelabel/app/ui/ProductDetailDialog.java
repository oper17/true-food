package com.barelabel.app.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.barelabel.app.FlaggedIngredientManager;

import java.util.Collection;

/**
 * Shared full-ingredients dialog: verdict summary line plus the ingredient
 * list with "clean highlight" (superior) ingredients emphasized. Used by both
 * the main verdict card and the scan-history screen.
 */
public final class ProductDetailDialog {

    private static final int C_GREEN_800 = android.graphics.Color.parseColor("#166534");
    private static final int C_RED_800 = android.graphics.Color.parseColor("#991B1B");

    private static final int C_GREEN_700 = android.graphics.Color.parseColor("#15803D");

    private ProductDetailDialog() {
    }

    public static void show(Context context, String title, String ingredients,
                            Collection<String> flagged) {
        SpannableStringBuilder content = new SpannableStringBuilder();

        // Verdict summary line
        boolean isClean = flagged == null || flagged.isEmpty();
        int verdictStart = content.length();
        if (isClean) {
            content.append("✓ Clean — no flagged categories\n\n");
        } else {
            content.append("⚠ Flagged: ")
                    .append(TextUtils.join(", ", flagged))
                    .append("\n\n");
        }
        content.setSpan(new StyleSpan(Typeface.BOLD),
                verdictStart, content.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        content.setSpan(new ForegroundColorSpan(isClean ? C_GREEN_800 : C_RED_800),
                verdictStart, content.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        // Ingredients header
        int headerStart = content.length();
        content.append("INGREDIENTS:\n\n");
        content.setSpan(new StyleSpan(Typeface.BOLD),
                headerStart, content.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        // Ingredient list with superior ("clean highlight") ingredients emphasized
        String[] tokens = ingredients == null ? new String[0] : ingredients.split(",");
        for (int i = 0; i < tokens.length; i++) {
            String trimmed = tokens[i].trim();
            if (trimmed.isEmpty()) continue;
            if (i > 0) content.append(", ");
            int tokenStart = content.length();
            content.append(trimmed);
            int tokenEnd = content.length();
            if (FlaggedIngredientManager.isSuperiorIngredient(context, trimmed)) {
                content.setSpan(new ForegroundColorSpan(C_GREEN_700),
                        tokenStart, tokenEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                content.setSpan(new StyleSpan(Typeface.BOLD),
                        tokenStart, tokenEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        TextView messageView = new TextView(context);
        messageView.setText(content);
        messageView.setTextSize(15f);
        messageView.setPadding(48, 32, 48, 16);
        messageView.setLineSpacing(1.2f, 1.1f);

        new AlertDialog.Builder(context)
                .setTitle(title == null ? "" : title)
                .setView(messageView)
                .setPositiveButton("Close", null)
                .show();
    }
}
