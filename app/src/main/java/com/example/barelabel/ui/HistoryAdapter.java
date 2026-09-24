package com.example.barelabel.ui;

import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.barelabel.AnalyticsTracker;
import com.example.barelabel.R;
import com.example.barelabel.model.ScannedProduct;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** RecyclerView adapter for the scan-history list, with an optional 2-pick compare mode. */
public class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.ViewHolder> {

    public interface Listener {
        void onItemClicked(ScannedProduct product);
        void onDeleteClicked(ScannedProduct product);
        void onSelectionChanged(int selectedCount);
    }

    private final Context context;
    private final Listener listener;
    private final List<ScannedProduct> items = new ArrayList<>();
    private final Set<String> selectedIds = new HashSet<>();
    private boolean selectionMode = false;
    /** True while programmatically unchecking a rejected (3rd) pick: don't log it. */
    private boolean suppressCheckLog = false;

    public HistoryAdapter(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    public void setItems(List<ScannedProduct> newItems) {
        items.clear();
        if (newItems != null) items.addAll(newItems);
        selectedIds.clear();
        notifyDataSetChanged();
    }

    public void setSelectionMode(boolean enabled) {
        selectionMode = enabled;
        selectedIds.clear();
        notifyDataSetChanged();
        listener.onSelectionChanged(0);
    }

    public boolean isSelectionMode() {
        return selectionMode;
    }

    /** Selected products in list order. */
    public List<ScannedProduct> getSelected() {
        List<ScannedProduct> out = new ArrayList<>();
        for (ScannedProduct p : items) {
            if (selectedIds.contains(p.id)) out.add(p);
        }
        return out;
    }

    public void removeItem(String id) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id.equals(id)) {
                items.remove(i);
                selectedIds.remove(id);
                notifyItemRemoved(i);
                break;
            }
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_history, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder h, int position) {
        ScannedProduct p = items.get(position);
        h.name.setText(p.displayName());

        String sub = timeAgo(p.timestamp);
        if (!TextUtils.isEmpty(p.brand)) sub = p.brand + " • " + sub;
        if (!TextUtils.isEmpty(p.gtin)) sub += " • " + p.gtin;
        h.subLine.setText(sub);

        boolean clean = p.clean;
        h.pill.setText(clean ? "✓ CLEAN" : "⚠ DIRTY");
        h.pill.setTextColor(Color.parseColor(clean ? "#166534" : "#991B1B"));
        h.pill.setBackgroundResource(clean ? R.drawable.chip_clean_background
                : R.drawable.chip_dirty_background);

        h.checkBox.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        h.deleteButton.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
        h.checkBox.setOnCheckedChangeListener(null);
        h.checkBox.setChecked(selectedIds.contains(p.id));

        boolean hasIngredients = !TextUtils.isEmpty(p.ingredients);
        h.checkBox.setEnabled(hasIngredients);
        h.itemView.setAlpha(hasIngredients || !selectionMode ? 1f : 0.5f);

        h.checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                if (selectedIds.size() >= 2) {
                    suppressCheckLog = true;
                    h.checkBox.setChecked(false);
                    suppressCheckLog = false;
                    Toast.makeText(context, "Pick only 2 products to compare",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                selectedIds.add(p.id);
            } else {
                selectedIds.remove(p.id);
            }
            if (!suppressCheckLog) {
                AnalyticsTracker.compareCheckboxToggled(isChecked, "history");
            }
            listener.onSelectionChanged(selectedIds.size());
        });

        h.deleteButton.setOnClickListener(v -> listener.onDeleteClicked(p));

        h.itemView.setOnClickListener(v -> {
            if (selectionMode) {
                if (!hasIngredients) {
                    Toast.makeText(context, "No ingredients saved for this product",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                h.checkBox.setChecked(!h.checkBox.isChecked());
            } else {
                listener.onItemClicked(p);
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView name, subLine, pill;
        ImageButton deleteButton;
        CheckBox checkBox;

        ViewHolder(View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.historyProductName);
            subLine = itemView.findViewById(R.id.historySubLine);
            pill = itemView.findViewById(R.id.historyVerdictPill);
            deleteButton = itemView.findViewById(R.id.historyDeleteButton);
            checkBox = itemView.findViewById(R.id.historySelectCheckBox);
        }
    }

    /** Compact relative timestamp: "just now", "5m ago", "3h ago", "Yesterday", "Oct 12". */
    public static String timeAgo(long timestamp) {
        long diff = System.currentTimeMillis() - timestamp;
        if (diff < 0) diff = 0;
        long minutes = diff / 60000;
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + "m ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h ago";
        long days = hours / 24;
        if (days == 1) return "Yesterday";
        if (days < 7) return days + "d ago";
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("MMM d",
                java.util.Locale.US);
        return fmt.format(new java.util.Date(timestamp));
    }
}
