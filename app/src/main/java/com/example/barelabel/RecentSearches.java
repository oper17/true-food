package com.example.barelabel;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * Persists the user's recent searches (most-recent-first, deduplicated,
 * capped) so the autocomplete dropdown can offer them as quick-tap bubbles.
 */
public class RecentSearches {

    private static final String KEY = "recent_searches";
    private static final int MAX_RECENTS = 8;

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    public static List<String> get(Context context) {
        List<String> out = new ArrayList<>();
        String raw = prefs(context).getString(KEY, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, "").trim();
                if (!s.isEmpty()) out.add(s);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void add(Context context, String query) {
        if (query == null) return;
        String q = query.trim();
        if (q.isEmpty()) return;
        List<String> recents = get(context);
        // Dedupe case-insensitively, then push to front.
        for (int i = recents.size() - 1; i >= 0; i--) {
            if (recents.get(i).equalsIgnoreCase(q)) recents.remove(i);
        }
        recents.add(0, q);
        while (recents.size() > MAX_RECENTS) recents.remove(recents.size() - 1);
        JSONArray arr = new JSONArray();
        for (String s : recents) arr.put(s);
        prefs(context).edit().putString(KEY, arr.toString()).apply();
    }
}
