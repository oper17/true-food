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
    /** Backoff after a 429: no new trial requests until this wall-clock time. */
    private static volatile long rateLimitedUntilMs = 0;
    private static final long RATE_LIMIT_COOLDOWN_MS = 60_000;

    /** Callback; always invoked on the main thread. {@code result} is null when unknown. */
    public interface PriceCallback {
        void onPrice(PriceResult result);
    }

    /** Transport/HTTP failure: never cached as a miss, always retried later. */
    private static final class PriceTransportException extends Exception {
        PriceTransportException(String msg) { super(msg); }
        PriceTransportException(String msg, Throwable cause) { super(msg, cause); }
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
            String p = currencySymbol() + String.format(Locale.US, "%.2f", price);
            return merchant.isEmpty() ? p : p + " · " + merchant;
        }

        private String currencySymbol() {
            try {
                return java.util.Currency.getInstance(currency).getSymbol(Locale.US);
            } catch (Exception e) {
                return "USD".equals(currency) ? "$" : currency + " ";
            }
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
        if (rateLimitedUntilMs > System.currentTimeMillis()) {
            // Trial quota exhausted: don't burn requests, report unknown.
            MAIN.post(() -> callback.onPrice(null));
            return;
        }
        // In-flight coalescing, race-free: the map lock makes exactly one
        // thread the leader; joiners can only attach before the leader's
        // remove, so no callback is ever stranded.
        final boolean leader;
        synchronized (IN_FLIGHT) {
            List<PriceCallback> existing = IN_FLIGHT.get(gtin);
            if (existing == null) {
                existing = new ArrayList<>();
                IN_FLIGHT.put(gtin, existing);
                leader = true;
            } else {
                leader = false;
            }
            existing.add(callback);
        }
        if (!leader) return;
        EXEC.execute(() -> {
            PriceResult result = null;
            try {
                result = USE_WORKER ? lookupViaWorker(gtin) : lookupViaUpcitemdb(gtin);
                if (result != null) {
                    CACHE.put(gtin, result);
                } else {
                    // Confirmed "no usable offer" — safe to remember.
                    MISS.add(gtin);
                }
            } catch (PriceTransportException e) {
                // Timeout / 429 / 5xx / bad response: NOT a miss. The next
                // bind retries instead of showing nothing forever.
                Log.w(TAG, "price lookup failed for " + gtin + "; not caching", e);
            }
            final List<PriceCallback> done;
            synchronized (IN_FLIGHT) {
                done = IN_FLIGHT.remove(gtin);
            }
            final PriceResult finalResult = result;
            MAIN.post(() -> {
                if (done != null) {
                    for (PriceCallback cb : done) {
                        cb.onPrice(finalResult);
                    }
                }
            });
        });
    }

    /**
     * @return cheapest in-stock offer, or null when UPCitemdb confirms it
     *         has no usable offer for this GTIN (a real miss).
     * @throws PriceTransportException on HTTP/transport failure (not a miss).
     */
    private static PriceResult lookupViaUpcitemdb(String gtin) throws PriceTransportException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(UPCITEMDB_TRIAL_URL + "?upc=" + gtin);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code == 429) {
                rateLimitedUntilMs = System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS;
                throw new PriceTransportException("UPCitemdb 429: trial quota exhausted");
            }
            if (code != 200) {
                throw new PriceTransportException("UPCitemdb HTTP " + code);
            }
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
        } catch (PriceTransportException e) {
            throw e;
        } catch (Exception e) {
            throw new PriceTransportException("UPCitemdb lookup failed for " + gtin, e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static PriceResult lookupViaWorker(String gtin) throws PriceTransportException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(WORKER_URL + "?gtin=" + gtin);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() != 200) {
                throw new PriceTransportException("worker HTTP " + conn.getResponseCode());
            }
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
        } catch (PriceTransportException e) {
            throw e;
        } catch (Exception e) {
            throw new PriceTransportException("Worker price lookup failed for " + gtin, e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Normalizes a raw GTIN/UPC: digits only, left-padded to 12, GS1 check
     * digit validated and repaired. Returns "" when unusable. GTIN-8 is
     * accepted (zero-extended); UPCitemdb coverage for those is uneven.
     */
    static String normalizeGtin(String raw) {
        if (raw == null) return "";
        String d = raw.replaceAll("\\D", "");
        if (d.length() < 8) return "";
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
