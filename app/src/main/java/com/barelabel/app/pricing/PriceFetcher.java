package com.barelabel.app.pricing;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Prototype price lookup for the alternates panel ("Exact matches" stack).
 *
 * <p>Current mode: queries the UPCitemdb keyless trial endpoint directly
 * (100 lookups/day per device IP, ~1 req/10s sustainable). Results are cached
 * in memory per GTIN so repeat views never burn quota.
 *
 * <p>When the Cloudflare Worker in {@code workers/pricing-proxy} is deployed,
 * flip {@link #USE_WORKER} and set {@link #WORKER_URL}: the worker holds the
 * (paid) API key server-side and caches per GTIN for 12h at the edge.
 *
 * <p>USDA {@code gtinUpc} values are often malformed (dropped leading zeros,
 * bad check digits), so every lookup first runs {@link #normalizeGtin}: digits
 * only, left-padded to 12, GS1 check digit repaired. UPCitemdb rejects invalid
 * codes outright, so this is required, not cosmetic.
 */
public final class PriceFetcher {
    private static final String TAG = "PriceFetcher";

    private static final String UPCITEMDB_TRIAL_URL =
            "https://api.upcitemdb.com/prod/trial/lookup";

    private static final boolean USE_WORKER = false;
    private static final String WORKER_URL =
            "https://barelabel-pricing.<your-account>.workers.dev/price";

    private static final ExecutorService EXEC = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, PriceResult> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> MISS = ConcurrentHashMap.newKeySet();
    private static final Map<String, List<PriceCallback>> IN_FLIGHT =
            new ConcurrentHashMap<>();

    /** Callback; always invoked on the main thread. {@code result} is null when unknown. */
    public interface PriceCallback {
        void onPrice(PriceResult result);
    }

    public static final class PriceResult {
        public final double price;
        public final String merchant;
        public final String currency;

        PriceResult(double price, String merchant, String currency) {
            this.price = price;
            this.merchant = merchant == null ? "" : merchant;
            this.currency = (currency == null || currency.isEmpty()) ? "USD" : currency;
        }

        /** e.g. "$11.38 · Walmart" or "$11.38" when no merchant is known. */
        public String displayText() {
            String p = "$" + String.format(Locale.US, "%.2f", price);
            return merchant.isEmpty() ? p : p + " · " + merchant;
        }
    }

    private PriceFetcher() {
    }

    /** Async price lookup by raw GTIN/UPC; callback fires on the main thread. */
    public static void fetch(String gtinRaw, PriceCallback callback) {
        final String gtin = normalizeGtin(gtinRaw);
        if (gtin.isEmpty()) {
            MAIN.post(() -> callback.onPrice(null));
            return;
        }
        PriceResult cached = CACHE.get(gtin);
        if (cached != null || MISS.contains(gtin)) {
            final PriceResult hit = cached;
            MAIN.post(() -> callback.onPrice(hit));
            return;
        }
        List<PriceCallback> waiters = IN_FLIGHT.get(gtin);
        if (waiters != null) {
            waiters.add(callback);
            return;
        }
        List<PriceCallback> fresh = new ArrayList<>();
        fresh.add(callback);
        IN_FLIGHT.put(gtin, fresh);
        EXEC.execute(() -> {
            PriceResult result = USE_WORKER ? lookupViaWorker(gtin) : lookupViaUpcitemdb(gtin);
            if (result != null) {
                CACHE.put(gtin, result);
            } else {
                MISS.add(gtin);
            }
            List<PriceCallback> done = IN_FLIGHT.remove(gtin);
            MAIN.post(() -> {
                if (done != null) {
                    for (PriceCallback cb : done) {
                        cb.onPrice(result);
                    }
                }
            });
        });
    }

    private static PriceResult lookupViaUpcitemdb(String gtin) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(UPCITEMDB_TRIAL_URL + "?upc=" + gtin);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() != 200) return null;
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) sb.append(line);
            in.close();
            JSONObject root = new JSONObject(sb.toString());
            if (!"OK".equals(root.optString("code"))) return null;
            JSONArray items = root.optJSONArray("items");
            if (items == null || items.length() == 0) return null;
            JSONArray offers = items.getJSONObject(0).optJSONArray("offers");
            if (offers == null) return null;
            PriceResult best = null;
            for (int i = 0; i < offers.length(); i++) {
                JSONObject o = offers.getJSONObject(i);
                String availability = o.optString("availability", "").toLowerCase(Locale.US);
                if (availability.contains("out of stock")) continue;
                double price = o.optDouble("price", Double.NaN);
                if (Double.isNaN(price) || price <= 0) continue;
                if (best == null || price < best.price) {
                    best = new PriceResult(price, o.optString("merchant", ""),
                            o.optString("currency", ""));
                }
            }
            return best;
        } catch (Exception e) {
            Log.w(TAG, "UPCitemdb lookup failed for " + gtin, e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static PriceResult lookupViaWorker(String gtin) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(WORKER_URL + "?gtin=" + gtin);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() != 200) return null;
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) sb.append(line);
            in.close();
            JSONObject root = new JSONObject(sb.toString());
            if (!root.optBoolean("ok", false) || root.isNull("price")) return null;
            return new PriceResult(root.optDouble("price", Double.NaN),
                    root.optString("merchant", ""), root.optString("currency", "USD"));
        } catch (Exception e) {
            Log.w(TAG, "Worker price lookup failed for " + gtin, e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Normalizes a raw GTIN/UPC: digits only, left-padded to 12, GS1 check
     * digit validated and repaired. Returns "" when unusable.
     */
    static String normalizeGtin(String raw) {
        if (raw == null) return "";
        String d = raw.replaceAll("\\D", "");
        if (d.length() < 11) return "";
        while (d.length() < 12) d = "0" + d;
        if (d.length() > 14) d = d.substring(d.length() - 14);
        String payload = d.substring(0, d.length() - 1);
        int want = gs1CheckDigit(payload);
        if (d.charAt(d.length() - 1) - '0' != want) {
            d = payload + want;
        }
        return d;
    }

    /** GS1 mod-10 check digit for the payload (all digits except the check digit). */
    static int gs1CheckDigit(String payload) {
        int sum = 0;
        boolean times3 = true;
        for (int i = payload.length() - 1; i >= 0; i--) {
            int v = payload.charAt(i) - '0';
            sum += times3 ? v * 3 : v;
            times3 = !times3;
        }
        return (10 - (sum % 10)) % 10;
    }
}
