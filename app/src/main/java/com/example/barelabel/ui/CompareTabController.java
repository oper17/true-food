package com.example.barelabel.ui;

import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.barelabel.AnalyticsTracker;
import com.example.barelabel.R;
import com.example.barelabel.ScanHistoryRepository;
import com.example.barelabel.model.ScannedProduct;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns the Compare tab: two dropdowns populated from scan history and an
 * inline side-by-side comparison of the selected pair.
 */
public class CompareTabController {

    private final AppCompatActivity activity;
    private final Spinner spinnerA;
    private final Spinner spinnerB;
    private final TextView hintText;
    private final LinearLayout resultContainer;
    private final ArrayAdapter<String> adapterA;
    private final ArrayAdapter<String> adapterB;
    private List<ScannedProduct> products = new ArrayList<>();
    /** Pair pushed from search-result compare boxes; merged ahead of history. */
    private List<ScannedProduct> pinnedExternal = new ArrayList<>();
    private boolean programmaticSelection;

    public CompareTabController(AppCompatActivity activity, View tabContent) {
        this.activity = activity;
        spinnerA = tabContent.findViewById(R.id.compareSpinnerA);
        spinnerB = tabContent.findViewById(R.id.compareSpinnerB);
        hintText = tabContent.findViewById(R.id.compareHintText);
        resultContainer = tabContent.findViewById(R.id.compareResultContainer);

        adapterA = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item, new ArrayList<>());
        adapterA.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        adapterB = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item, new ArrayList<>());
        adapterB.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerA.setAdapter(adapterA);
        spinnerB.setAdapter(adapterB);

        AdapterView.OnItemSelectedListener listener =
                new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> parent, View view,
                                               int position, long id) {
                        if (!programmaticSelection) pinnedExternal.clear();
                        maybeRender();
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {
                        maybeRender();
                    }
                };
        spinnerA.setOnItemSelectedListener(listener);
        spinnerB.setOnItemSelectedListener(listener);
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

    /** Reload the history on a background thread, then repopulate the dropdowns. */
    public void refresh() {
        new Thread(() -> {
            final List<ScannedProduct> items = ScanHistoryRepository.getAll(activity);
            final List<ScannedProduct> pinned = new ArrayList<>(pinnedExternal);
            activity.runOnUiThread(() -> {
                // Pinned search picks first, then history (deduped by name+brand).
                List<ScannedProduct> merged = new ArrayList<>(pinned);
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (ScannedProduct p : pinned) seen.add(productKey(p));
                for (ScannedProduct p : items) {
                    if (seen.add(productKey(p))) merged.add(p);
                }
                products = merged;
                List<String> names = new ArrayList<>();
                for (ScannedProduct p : merged) names.add(p.displayName());
                programmaticSelection = true;
                try {
                    adapterA.clear();
                    adapterA.addAll(names);
                    adapterB.clear();
                    adapterB.addAll(names);
                    if (!pinned.isEmpty()) {
                        int i = indexOfKey(productKey(pinned.get(0)));
                        if (i >= 0) spinnerA.setSelection(i);
                        if (pinned.size() > 1) {
                            int j = indexOfKey(productKey(pinned.get(1)));
                            if (j >= 0) spinnerB.setSelection(j);
                        }
                    } else if (adapterA.getCount() > 0) {
                        // Keep selections valid; default to the first two distinct items.
                        if (spinnerA.getSelectedItemPosition() < 0
                                || spinnerA.getSelectedItemPosition() >= adapterA.getCount()) {
                            spinnerA.setSelection(0);
                        }
                        int bPos = spinnerB.getSelectedItemPosition();
                        if (bPos < 0 || bPos >= adapterB.getCount()
                                || (adapterB.getCount() > 1
                                    && bPos == spinnerA.getSelectedItemPosition())) {
                            spinnerB.setSelection(
                                    spinnerA.getSelectedItemPosition() == 0 ? 1 : 0);
                        }
                    }
                } finally {
                    programmaticSelection = false;
                }
                maybeRender();
            });
        }).start();
    }

    private void maybeRender() {
        resultContainer.removeAllViews();
        int i = spinnerA.getSelectedItemPosition();
        int j = spinnerB.getSelectedItemPosition();
        boolean valid = i >= 0 && j >= 0
                && i < products.size() && j < products.size() && i != j;
        if (!valid) {
            hintText.setVisibility(View.VISIBLE);
            hintText.setText(products.size() < 2
                    ? "Scan at least two products to compare them."
                    : "Select two different products to compare.");
            return;
        }
        hintText.setVisibility(View.GONE);
        AnalyticsTracker.compareOpened();
        CompareViewBuilder.buildComparison(activity, resultContainer,
                products.get(i), products.get(j));
    }

    private static String productKey(ScannedProduct p) {
        String name = p.name == null ? "" : p.name.trim().toLowerCase(java.util.Locale.US);
        String brand = p.brand == null ? "" : p.brand.trim().toLowerCase(java.util.Locale.US);
        return name + "|" + brand;
    }

    private int indexOfKey(String key) {
        for (int i = 0; i < products.size(); i++) {
            if (productKey(products.get(i)).equals(key)) return i;
        }
        return -1;
    }
}
