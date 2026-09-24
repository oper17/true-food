package com.example.barelabel.ui;

import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.barelabel.R;
import com.example.barelabel.ScanHistoryRepository;
import com.example.barelabel.model.ScannedProduct;

/**
 * Owns the History tab: loads the persisted scan history (newest first),
 * renders it, handles X deletes, and re-opens saved ingredient dialogs on tap.
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
                ProductDetailDialog.show(activity, product);
            }

            @Override
            public void onDeleteClicked(ScannedProduct product) {
                new Thread(() -> {
                    ScanHistoryRepository.delete(activity, product.id);
                    refresh();
                }).start();
            }
        });
        recyclerView.setLayoutManager(new LinearLayoutManager(activity));
        recyclerView.setAdapter(adapter);
    }

    /** Reload from storage on a background thread, then render. */
    public void refresh() {
        new Thread(() -> {
            final java.util.List<ScannedProduct> items = ScanHistoryRepository.getAll(activity);
            activity.runOnUiThread(() -> {
                adapter.setItems(items);
                boolean empty = items.isEmpty();
                emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
                recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
            });
        }).start();
    }
}
