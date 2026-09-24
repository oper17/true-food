package com.barelabel.app.ui;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.barelabel.app.R;
import com.barelabel.app.ScanHistoryRepository;
import com.barelabel.app.model.ScannedProduct;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Owns the Compare tab: renders a side-by-side comparison of a product pair.
 * The pair comes from the search-result Compare boxes (pinned); opening the
 * tab directly falls back to the two most recent scans. Each product column
 * carries Buy and Save actions via the {@link CompareViewBuilder.CompareActionListener}.
 */
public class CompareTabController {

    private final AppCompatActivity activity;
    private final TextView hintText;
    private final LinearLayout resultContainer;
    /** Pair pushed from search-result compare boxes; rendered ahead of history. */
    private List<ScannedProduct> pinnedExternal = new ArrayList<>();
    private CompareViewBuilder.CompareActionListener actionListener;

    public CompareTabController(AppCompatActivity activity, View tabContent) {
        this.activity = activity;
        hintText = tabContent.findViewById(R.id.compareHintText);
        resultContainer = tabContent.findViewById(R.id.compareResultContainer);
    }

    public void setCompareActionListener(CompareViewBuilder.CompareActionListener listener) {
        this.actionListener = listener;
    }

    /**
     * Pin a pair from the search results (they may not be in history).
     * Takes effect on the next refresh(), which the caller triggers by
     * selecting the Compare tab.
     */
    public void compareExternal(ScannedProduct a, ScannedProduct b) {
        pinnedExternal = new ArrayList<>();
        if (a != null) pinnedExternal.add(a);
        if (b != null) pinnedExternal.add(b);
    }

    /** Drop the pinned pair (e.g. on a fresh search). */
    public void clearExternal() {
        pinnedExternal = new ArrayList<>();
    }

    /** Reload the history on a background thread, then render the pair. */
    public void refresh() {
        new Thread(() -> {
            final List<ScannedProduct> items = ScanHistoryRepository.getAll(activity);
            final List<ScannedProduct> pinned = new ArrayList<>(pinnedExternal);
            activity.runOnUiThread(() -> {
                // Pinned search picks first, then history (deduped by name+brand).
                List<ScannedProduct> candidates = new ArrayList<>(pinned);
                Set<String> seen = new HashSet<>();
                for (ScannedProduct p : pinned) seen.add(productKey(p));
                for (ScannedProduct p : items) {
                    if (seen.add(productKey(p))) candidates.add(p);
                }
                Set<String> savedKeys = new HashSet<>();
                for (ScannedProduct p : items) savedKeys.add(productKey(p));
                render(candidates, savedKeys, !pinned.isEmpty());
            });
        }).start();
    }

    private void render(List<ScannedProduct> candidates, Set<String> savedKeys,
                        boolean hasPinnedPair) {
        resultContainer.removeAllViews();
        if (candidates.size() < 2) {
            hintText.setVisibility(View.VISIBLE);
            hintText.setText("Scan or save two products to compare them.");
            return;
        }
        hintText.setVisibility(hasPinnedPair ? View.GONE : View.VISIBLE);
        if (!hasPinnedPair) {
            hintText.setText("Showing your two most recent scans. "
                    + "Use the Compare boxes in Search to pick any two products.");
        }
        CompareViewBuilder.buildComparison(activity, resultContainer,
                candidates.get(0), candidates.get(1), savedKeys, actionListener);
    }

    private static String productKey(ScannedProduct p) {
        String name = p.name == null ? "" : p.name.trim().toLowerCase(Locale.US);
        String brand = p.brand == null ? "" : p.brand.trim().toLowerCase(Locale.US);
        return name + "|" + brand;
    }
}
