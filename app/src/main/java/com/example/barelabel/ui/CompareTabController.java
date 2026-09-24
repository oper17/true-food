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
 */
public class CompareTabController {

    private final AppCompatActivity activity;
    private final Spinner spinnerA;
    private final Spinner spinnerB;
    private final LinearLayout resultContainer;
    private final TextView emptyHint;
    private final List<ScannedProduct> products = new ArrayList<>();

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
        new Thread(() -> {
            List<ScannedProduct> all = ScanHistoryRepository.getAll(activity);
            List<ScannedProduct> comparable = new ArrayList<>();
            for (ScannedProduct p : all) {
                if (p.ingredients != null && !p.ingredients.trim().isEmpty()) {
                    comparable.add(p);
                }
            }
            activity.runOnUiThread(() -> {
                products.clear();
                products.addAll(comparable);
                List<String> names = new ArrayList<>();
                for (ScannedProduct p : products) {
                    names.add(p.displayName() + (p.clean ? "  ✓" : "  ⚠"));
                }
                ArrayAdapter<String> adapter = new ArrayAdapter<>(
                        activity, android.R.layout.simple_spinner_item, names);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                spinnerA.setAdapter(adapter);
                spinnerB.setAdapter(adapter);
                if (products.size() >= 2) {
                    spinnerA.setSelection(0);
                    spinnerB.setSelection(1);
                }
                boolean enough = products.size() >= 2;
                emptyHint.setVisibility(enough ? View.GONE : View.VISIBLE);
                spinnerA.setEnabled(enough);
                spinnerB.setEnabled(enough);
            });
        }).start();
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
        ScannedProduct a = products.get(ia);
        ScannedProduct b = products.get(ib);
        resultContainer.removeAllViews();
        resultContainer.addView(CompareViewBuilder.build(activity, a, b));
        AnalyticsTracker.compareOpened();
    }
}
