package com.barelabel.app;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import java.util.Set;

/**
 * Offline unbranded food completions backed by a prefix map generated from
 * USDA SR Legacy generic food descriptions (~7,800 terms).
 *
 * Asset layout: {"terms": [...], "map": {"prefix": [termIndex, ...]}}.
 * Prefixes longer than the stored cap fall back to progressively shorter
 * prefixes, which is safe: every completion of a prefix is also a completion
 * of its shorter prefixes' superset... more precisely, the stored list for a
 * shorter prefix contains the same top-ranked completions.
 */
public class UnbrandedSuggestionProvider {

    private static final String TAG = "UnbrandedSuggestions";
    private static final String ASSET_PATH = "unbranded_prefix_map.json";
    private static final int MIN_PREFIX_LEN = 2;

    private final Context appContext;
    private boolean loaded = false;
    private JSONArray terms;
    private JSONObject prefixMap;
    private Set<String> termSet = null;

    public UnbrandedSuggestionProvider(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /** Returns up to {@code max} unbranded completions for the query prefix. */
    public synchronized List<String> getCompletions(String query, int max) {
        List<String> out = new ArrayList<>();
        if (max <= 0) return out;
        ensureLoaded();
        if (prefixMap == null || terms == null) return out;

        String q = query == null ? "" : query.toLowerCase(Locale.US).trim();
        while (q.length() >= MIN_PREFIX_LEN) {
            JSONArray ids = prefixMap.optJSONArray(q);
            if (ids != null) {
                for (int i = 0; i < ids.length() && out.size() < max; i++) {
                    int idx = ids.optInt(i, -1);
                    if (idx >= 0 && idx < terms.length()) {
                        String term = terms.optString(idx, "");
                        if (!term.isEmpty() && !out.contains(term)) {
                            out.add(term);
                        }
                    }
                }
                break;
            }
            q = q.substring(0, q.length() - 1);
        }
        return out;
    }

    private void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try (InputStream is = appContext.getAssets().open(ASSET_PATH)) {
            Scanner s = new Scanner(is, StandardCharsets.UTF_8.name()).useDelimiter("\\A");
            String json = s.hasNext() ? s.next() : "";
            JSONObject root = new JSONObject(json);
            terms = root.getJSONArray("terms");
            prefixMap = root.getJSONObject("map");
            Log.i(TAG, "Loaded " + terms.length() + " unbranded terms");
        } catch (Exception e) {
            Log.e(TAG, "Failed to load unbranded prefix map", e);
        }
    }

    /**
     * True when the label is one of the offline unbranded dictionary terms.
     * Stateless — safe to call from any thread, unlike a "last results"
     * cache written on a worker and read on the UI thread.
     */
    public synchronized boolean isKnownTerm(String term) {
        ensureLoaded();
        if (termSet == null) {
            termSet = new HashSet<>();
            if (terms != null) {
                for (int i = 0; i < terms.length(); i++) {
                    String t = terms.optString(i, "");
                    if (!t.isEmpty()) termSet.add(t);
                }
            }
        }
        return term != null && termSet.contains(term);
    }
}
