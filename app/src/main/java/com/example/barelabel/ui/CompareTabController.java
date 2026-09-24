package com.example.barelabel.ui;

import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.barelabel.AnalyticsTracker;
import com.example.barelabel.R;
import com.example.barelabel.ScanHistoryRepository;
import com.example.barelabel.model.ScannedProduct;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns the Compare tab: two product pickers fed by scan history, a Compare
 * button, and the side-by-side result rendered inline below the pickers.
 * The host can also push an externally chosen pair (e.g. from search
 * results) via {@link #compareExternal}, which pins those two products at
 * the top of the pickers and renders the comparison immediately.
 */
public class CompareTabController {

    private final AppCompatActivity activity;
    private final Spinner spinnerA;
    private final Spinner spinnerB;
    private final LinearLayout resultContainer;
    private final TextView emptyHint;
    private final List<ScannedProduct> products = new ArrayList<>();
    /** Bumped by compareExternal so a stale history refresh can't clobber it. */
    private int generation = 0;

    public CompareTabController(AppCompatActivity activity, View tabContent) {
        this.activity = activity;
        spinnerA = tabContent.findViewById(R.id.compareSpinnerA);
        spinnerB = tabContent.findViewById(R.id.compareSpinnerB);
        resultContainer = tabContent.findViewById(R.id.compareResultContainer);
        emptyHint = tabContent.findViewById(R.id.compareEmptyHint);
        tabContent.findViewById(R.id.compareRunButton).setOnClickListener(v -> runCompare());
    }

    /** Reload the product pickers from storage; call when the tab is selected. */
    public void refresh() {
        final int gen = generation;
        new Thread(() -> {
            List<ScannedProduct> comparable = loadComparable();
            activity.runOnUiThread(() -> {
                if (gen != generation) return; // superseded by compareExternal
                setProducts(comparable, 0, 1);
            });
        }).start();
    }

    /**
     * Compare two products chosen outside the tab (e.g. search results).
     * They are pinned at the top of both pickers and compared immediately;
     * the user can still re-pick from history via the spinners afterwards.
     */
    public void compareExternal(ScannedProduct a, ScannedProduct b) {
        generation++;
        final int gen = generation;
        new Thread(() -> {
            List<ScannedProduct> combined = new ArrayList<>();
            combined.add(a);
            combined.add(b);
            for (ScannedProduct p : loadComparable()) {
                if (p.id.equals(a.id) || p.id.equals(b.id)) continue;
                combined.add(p);
            }
            activity.runOnUiThread(() -> {
                if (gen != generation) return;
                setProducts(combined, 0, 1);
                renderComparison(0, 1);
                AnalyticsTracker.compareOpened();
            });
        }).start();
    }

    private List<ScannedProduct> loadComparable() {
        List<ScannedProduct> all = ScanHistoryRepository.getAll(activity);
        List<ScannedProduct> comparable = new ArrayList<>();
        for (ScannedProduct p : all) {
            if (p.ingredients != null && !p.ingredients.trim().isEmpty()) {
                comparable.add(p);
            }
        }
        return comparable;
    }

    private void setProducts(List<ScannedProduct> list, int selectA, int selectB) {
        products.clear();
        products.addAll(list);
        List<String> names = new ArrayList<>();
        for (ScannedProduct p : products) {
            names.add(p.displayName() + (p.clean ? "  ✓" : "  ⚠"));
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                activity, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerA.setAdapter(adapter);
        spinnerB.setAdapter(adapter);
        boolean enough = products.size() >= 2;
        if (enough) {
            spinnerA.setSelection(Math.min(selectA, products.size() - 1));
            spinnerB.setSelection(Math.min(selectB, products.size() - 1));
        }
        emptyHint.setVisibility(enough ? View.GONE : View.VISIBLE);
        spinnerA.setEnabled(enough);
        spinnerB.setEnabled(enough);
    }

    private void runCompare() {
        int ia = spinnerA.getSelectedItemPosition();
        int ib = spinnerB.getSelectedItemPosition();
        if (ia < 0 || ib < 0 || ia >= products.size() || ib >= products.size()) {
            Toast.makeText(activity, "Scan some products first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (ia == ib) {
            Toast.makeText(activity, "Pick two different products", Toast.LENGTH_SHORT).show();
            return;
        }
        renderComparison(ia, ib);
        AnalyticsTracker.compareOpened();
    }

    private void renderComparison(int ia, int ib) {
        ScannedProduct a = products.get(ia);
        ScannedProduct b = products.get(ib);
        resultContainer.removeAllViews();
        resultContainer.addView(CompareViewBuilder.build(activity, a, b));
    }
}
