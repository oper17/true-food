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

    /** Reload the history on a background thread, then repopulate the dropdowns. */
    public void refresh() {
        new Thread(() -> {
            final List<ScannedProduct> items = ScanHistoryRepository.getAll(activity);
            activity.runOnUiThread(() -> {
                products = items;
                List<String> names = new ArrayList<>();
                for (ScannedProduct p : items) names.add(p.displayName());
                adapterA.clear();
                adapterA.addAll(names);
                adapterB.clear();
                adapterB.addAll(names);
                // Keep selections valid; default to the first two distinct items.
                if (adapterA.getCount() > 0) {
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
}
