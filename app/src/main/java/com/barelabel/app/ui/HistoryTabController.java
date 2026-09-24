package com.barelabel.app.ui;

import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.barelabel.app.R;
import com.barelabel.app.ScanHistoryRepository;
import com.barelabel.app.model.ScannedProduct;

import java.util.List;

/**
 * Owns the History tab: newest-first persisted scan history, per-item
 * delete, and tap-to-reopen of the saved ingredient dialog.
 */
public class HistoryTabController {

    private final AppCompatActivity activity;
    private final RecyclerView recyclerView;
    private final View emptyState;
    private final HistoryAdapter adapter;

    public HistoryTabController(AppCompatActivity activity, View tabContent) {
        this.activity = activity;
        recyclerView = tabContent.findViewById(R.id.historyRecyclerView);
        emptyState = tabContent.findViewById(R.id.historyEmptyState);
        adapter = new HistoryAdapter(activity, new HistoryAdapter.Listener() {
            @Override
            public void onItemClicked(ScannedProduct product) {
                ProductDetailDialog.show(activity, product.displayName(),
                        product.ingredients, product.flagged);
            }

            @Override
            public void onDeleteClicked(ScannedProduct product) {
                new Thread(() -> {
                    ScanHistoryRepository.delete(activity, product.id);
                    activity.runOnUiThread(() -> {
                        adapter.removeItem(product.id);
                        updateEmptyState();
                        Toast.makeText(activity, "Removed from history",
                                Toast.LENGTH_SHORT).show();
                    });
                }).start();
            }

            @Override
            public void onSelectionChanged(int selectedCount) {
                // Selection mode is not used in the tab UI.
            }
        });
        recyclerView.setLayoutManager(new LinearLayoutManager(activity));
        recyclerView.setAdapter(adapter);
    }

    /** Reload from storage on a background thread, then render. */
    public void refresh() {
        new Thread(() -> {
            final List<ScannedProduct> items = ScanHistoryRepository.getAll(activity);
            activity.runOnUiThread(() -> {
                adapter.setItems(items);
                updateEmptyState();
            });
        }).start();
    }

    private void updateEmptyState() {
        boolean empty = adapter.getItemCount() == 0;
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
    }
}
