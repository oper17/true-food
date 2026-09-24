package com.example.barelabel;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.barelabel.model.ScannedProduct;
import com.example.barelabel.ui.CompareSheetFragment;
import com.example.barelabel.ui.HistoryAdapter;
import com.example.barelabel.ui.ProductDetailDialog;

import java.util.List;

/**
 * Scan history: newest-first list of past verdicts with per-item delete,
 * plus a 2-pick compare mode that opens a side-by-side bottom sheet.
 */
public class HistoryActivity extends AppCompatActivity implements HistoryAdapter.Listener {

    private HistoryAdapter adapter;
    private RecyclerView recyclerView;
    private TextView emptyState;
    private Button compareToggleButton;
    private LinearLayout compareActionBar;
    private Button compareGoButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);
        setTitle("Scan history");

        recyclerView = findViewById(R.id.historyRecyclerView);
        emptyState = findViewById(R.id.historyEmptyState);
        compareToggleButton = findViewById(R.id.compareToggleButton);
        compareActionBar = findViewById(R.id.compareActionBar);
        compareGoButton = findViewById(R.id.compareGoButton);

        adapter = new HistoryAdapter(this, this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        compareToggleButton.setOnClickListener(v -> {
            boolean entering = !adapter.isSelectionMode();
            adapter.setSelectionMode(entering);
            compareToggleButton.setText(entering ? "Done" : "Compare");
            compareActionBar.setVisibility(View.GONE);
        });

        compareGoButton.setOnClickListener(v -> {
            List<ScannedProduct> selected = adapter.getSelected();
            if (selected.size() == 2) {
                AnalyticsTracker.compareOpened();
                CompareSheetFragment.newInstance(selected.get(0), selected.get(1))
                        .show(getSupportFragmentManager(), "compare");
            }
        });

        loadHistory();
    }

    private void loadHistory() {
        new Thread(() -> {
            List<ScannedProduct> items = ScanHistoryRepository.getAll(HistoryActivity.this);
            runOnUiThread(() -> {
                adapter.setItems(items);
                updateEmptyState();
            });
        }).start();
    }

    private void updateEmptyState() {
        boolean empty = adapter.getItemCount() == 0;
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty && adapter.isSelectionMode()) {
            adapter.setSelectionMode(false);
            compareToggleButton.setText("Compare");
        }
    }

    @Override
    public void onItemClicked(ScannedProduct product) {
        ProductDetailDialog.show(this, product.displayName(),
                product.ingredients, product.flagged);
    }

    @Override
    public void onDeleteClicked(ScannedProduct product) {
        new Thread(() -> {
            ScanHistoryRepository.delete(HistoryActivity.this, product.id);
            runOnUiThread(() -> {
                adapter.removeItem(product.id);
                updateEmptyState();
                Toast.makeText(this, "Removed from history", Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    @Override
    public void onSelectionChanged(int selectedCount) {
        boolean ready = selectedCount == 2;
        compareActionBar.setVisibility(ready ? View.VISIBLE : View.GONE);
        if (ready) compareGoButton.setText("Compare selected (2)");
    }
}
