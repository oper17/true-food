package com.example.barelabel.ui;

import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.barelabel.R;
import com.example.barelabel.model.ScannedProduct;

import java.util.ArrayList;
import java.util.List;

/**
 * RecyclerView adapter for the History tab: newest-first rows with a
 * CLEAN/DIRTY pill, a brand/timestamp sub-line, and an X button to delete.
 */
public class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.ViewHolder> {

    public interface Listener {
        void onItemClicked(ScannedProduct product);
        void onDeleteClicked(ScannedProduct product);
    }

    private final Context context;
    private final Listener listener;
    private final List<ScannedProduct> items = new ArrayList<>();

    public HistoryAdapter(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    public void setItems(List<ScannedProduct> newItems) {
        items.clear();
        if (newItems != null) items.addAll(newItems);
        notifyDataSetChanged();
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

        h.deleteButton.setOnClickListener(v -> listener.onDeleteClicked(p));
        h.itemView.setOnClickListener(v -> listener.onItemClicked(p));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView name, subLine, pill;
        ImageButton deleteButton;

        ViewHolder(View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.historyProductName);
            subLine = itemView.findViewById(R.id.historySubLine);
            pill = itemView.findViewById(R.id.historyVerdictPill);
            deleteButton = itemView.findViewById(R.id.historyDeleteButton);
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
