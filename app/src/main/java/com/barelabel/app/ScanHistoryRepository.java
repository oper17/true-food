package com.barelabel.app;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.barelabel.app.model.ProductResult;
import com.barelabel.app.model.ScannedProduct;

import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * File-backed scan history. Lives in the app's internal files directory, so it
 * persists across sessions AND app updates (it is only cleared on uninstall or
 * "clear data"). All methods do disk I/O — call them off the main thread.
 *
 * Entries are deduplicated by product (name + brand): re-scanning a product
 * bumps it to the top instead of creating a duplicate row.
 */
public final class ScanHistoryRepository {

    private static final String TAG = "ScanHistory";
    private static final String FILE_NAME = "scan_history.json";
    private static final int MAX_ENTRIES = 100;

    private ScanHistoryRepository() {
    }

    /** Persist a scan. No-op for not-found results or products without ingredients. */
    public static synchronized void saveScan(Context context, ProductResult result) {
        if (context == null || result == null || !result.found) return;
        if (TextUtils.isEmpty(result.ingredients) || result.ingredients.trim().isEmpty()) return;

        List<ScannedProduct> all = readAll(context);
        String key = dedupeKey(result.name, result.brand);
        for (Iterator<ScannedProduct> it = all.iterator(); it.hasNext(); ) {
            ScannedProduct existing = it.next();
            if (dedupeKey(existing.name, existing.brand).equals(key)) {
                it.remove(); // re-scan: drop the old row, fresh one goes on top
                break;
            }
        }
        all.add(0, ScannedProduct.fromProductResult(result));
        while (all.size() > MAX_ENTRIES) all.remove(all.size() - 1);
        writeAll(context, all);
    }

    /** Newest-first list of saved scans. */
    public static synchronized List<ScannedProduct> getAll(Context context) {
        return readAll(context);
    }

    /** Delete one entry by id. */
    public static synchronized void delete(Context context, String id) {
        if (context == null || id == null) return;
        List<ScannedProduct> all = readAll(context);
        for (Iterator<ScannedProduct> it = all.iterator(); it.hasNext(); ) {
            if (id.equals(it.next().id)) {
                it.remove();
                break;
            }
        }
        writeAll(context, all);
    }

    private static String dedupeKey(String name, String brand) {
        String n = name == null ? "" : name.trim().toLowerCase(Locale.US);
        String b = brand == null ? "" : brand.trim().toLowerCase(Locale.US);
        return n + "|" + b;
    }

    private static List<ScannedProduct> readAll(Context context) {
        List<ScannedProduct> out = new ArrayList<>();
        if (context == null) return out;
        try (FileInputStream fis = context.openFileInput(FILE_NAME);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(fis, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                ScannedProduct p = ScannedProduct.fromJson(arr.optJSONObject(i));
                if (p != null) out.add(p);
            }
        } catch (java.io.FileNotFoundException e) {
            // No history yet — not an error.
        } catch (Exception e) {
            Log.w(TAG, "Failed to read scan history", e);
        }
        return out;
    }

    private static void writeAll(Context context, List<ScannedProduct> all) {
        if (context == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (ScannedProduct p : all) arr.put(p.toJson());
            try (FileOutputStream fos = context.openFileOutput(FILE_NAME, Context.MODE_PRIVATE)) {
                fos.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to write scan history", e);
        }
    }
}
