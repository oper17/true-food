package com.barelabel.app.images;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Resolves product info for a GTIN via Open Food Facts.
 *
 * Returns the front image URL plus OFF's friendlier product naming
 * (product_name / brands / quantity), which is better for on-screen display
 * and shopping search than USDA's terse descriptions.
 *
 * Lookup chain: in-memory cache -> 7-day disk cache -> OFF network request
 * (tries the GTIN as-is, then with leading zeros stripped). Genuine misses
 * (OFF confirms the product is absent) are negative-cached as JSON,
 * including empty files, so a barcode is only ever looked up once per TTL
 * window. Transport failures are NEVER negative-cached — only a confirmed
 * OFF "not found" earns a negative entry; failures retry (with a short
 * in-memory cooldown) instead of blackholing the barcode for 7 days.
 */
public class ProductImageResolver {

    private static final String TAG = "ProductImageResolver";
    private static final String OFF_PRODUCT_URL =
            "https://world.openfoodfacts.org/api/v2/product/";
    private static final String USER_AGENT =
            "BareLabel/1.0 (Android food ingredient screening app)";
    private static final long TTL_MILLIS = TimeUnit.DAYS.toMillis(7);
    private static final int MEMORY_CACHE_SIZE = 200;

    /** Product info from Open Food Facts; null imageUrl/name means absent. */
    public static final class OffProductInfo {
        public final String imageUrl;
        public final String name;
        public final String brands;
        public final String quantity;

        public OffProductInfo(String imageUrl, String name,
                              String brands, String quantity) {
            this.imageUrl = imageUrl == null ? "" : imageUrl;
            this.name = name == null ? "" : name;
            this.brands = brands == null ? "" : brands;
            this.quantity = quantity == null ? "" : quantity;
        }

        public boolean hasImage() {
            return !imageUrl.isEmpty();
        }

        public boolean hasName() {
            return !name.isEmpty();
        }

        /** Friendly display name, e.g. "Peanut Butter Crunchy (380 g)". */
        public String displayName() {
            if (name.isEmpty()) return "";
            return quantity.isEmpty() ? name : name + " (" + quantity + ")";
        }

        /** Shopping-search query: brands + product name + quantity. */
        public String shoppingQuery() {
            StringBuilder q = new StringBuilder();
            if (!brands.isEmpty()) q.append(brands).append(' ');
            q.append(name);
            if (!quantity.isEmpty()) q.append(' ').append(quantity);
            return q.toString().trim();
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("image_url", imageUrl);
                o.put("name", name);
                o.put("brands", brands);
                o.put("quantity", quantity);
            } catch (Exception e) {
                Log.w(TAG, "toJson failed", e);
            }
            return o;
        }

        static OffProductInfo fromJson(String json) {
            try {
                JSONObject o = new JSONObject(json);
                return new OffProductInfo(
                        o.optString("image_url", ""),
                        o.optString("name", ""),
                        o.optString("brands", ""),
                        o.optString("quantity", ""));
            } catch (Exception e) {
                return null;
            }
        }
    }

    public interface Callback {
        /** Called on the main thread; info is null only on total lookup failure. */
        void onResult(OffProductInfo info);
    }

    private static final ExecutorService executor =
            Executors.newFixedThreadPool(2);
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Synchronized LRU memory cache: gtin -> OffProductInfo (null = known miss).
    private static final Map<String, OffProductInfo> memoryCache =
            new LinkedHashMap<String, OffProductInfo>(MEMORY_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<String, OffProductInfo> e) {
                    return size() > MEMORY_CACHE_SIZE;
                }
            };

    // Callbacks waiting on an in-flight network fetch, by GTIN: concurrent
    // binds of the same barcode join the pending request instead of each
    // firing their own OFF lookup.
    private static final Map<String, List<Callback>> inFlight = new HashMap<>();

    // Last transport-failure time by GTIN (memory only): a failed lookup
    // isn't negative-cached, but it does cool down briefly so a network
    // outage doesn't refire on every bind.
    private static final Map<String, Long> recentFailures = new HashMap<>();
    private static final long FAILURE_COOLDOWN_MS = 60_000;

    private ProductImageResolver() {
    }

    /** Async resolve; callback always fires on the main thread. */
    public static void resolve(Context context, String gtin, Callback callback) {
        final Context appContext = context.getApplicationContext();
        final String key = normalize(gtin);
        if (key.isEmpty()) {
            post(callback, null);
            return;
        }
        synchronized (memoryCache) {
            if (memoryCache.containsKey(key)) {
                post(callback, memoryCache.get(key));
                return;
            }
        }
        if (inFailureCooldown(key)) {
            post(callback, null);
            return;
        }
        synchronized (inFlight) {
            List<Callback> waiters = inFlight.get(key);
            if (waiters != null) {
                waiters.add(callback);
                return;
            }
            waiters = new ArrayList<>();
            waiters.add(callback);
            inFlight.put(key, waiters);
        }
        executor.execute(() -> {
            OffProductInfo result = null;
            try {
                OffProductInfo disk = readDiskCache(appContext, key);
                if (disk != null || diskHas(key, appContext)) {
                    result = disk; // null here = negatively cached genuine miss
                } else {
                    // Throws on transport failure: only a confirmed OFF
                    // "not found" is negative-cached below.
                    result = fetchFromOff(key);
                    writeDiskCache(appContext, key, result);
                }
                putMemory(key, result);
            } catch (IOException e) {
                Log.w(TAG, "OFF unreachable for " + key + "; not negative-caching", e);
                markFailure(key);
            }
            List<Callback> done;
            synchronized (inFlight) {
                done = inFlight.remove(key);
            }
            if (done != null) {
                for (Callback cb : done) post(cb, result);
            }
        });
    }

    /**
     * Synchronous memory-only lookup. Never touches disk or network — safe
     * on the UI thread. Call {@link #warmFromDisk} when binding (e.g. the
     * verdict card) so Buy-flow lookups hit memory.
     */
    public static OffProductInfo getCached(Context context, String gtin) {
        String key = normalize(gtin);
        if (key.isEmpty()) return null;
        synchronized (memoryCache) {
            return memoryCache.containsKey(key) ? memoryCache.get(key) : null;
        }
    }

    /**
     * Pre-loads a disk entry into the memory cache on a worker. Fire-and-
     * forget; safe to call on the UI thread alongside {@link #resolve}.
     */
    public static void warmFromDisk(Context context, String gtin) {
        final Context appContext = context.getApplicationContext();
        final String key = normalize(gtin);
        if (key.isEmpty()) return;
        synchronized (memoryCache) {
            if (memoryCache.containsKey(key)) return;
        }
        executor.execute(() -> {
            OffProductInfo disk = readDiskCache(appContext, key);
            if (disk != null || diskHas(key, appContext)) {
                putMemory(key, disk);
            }
        });
    }

    private static boolean inFailureCooldown(String key) {
        synchronized (recentFailures) {
            Long t = recentFailures.get(key);
            if (t == null) return false;
            if (System.currentTimeMillis() - t > FAILURE_COOLDOWN_MS) {
                recentFailures.remove(key);
                return false;
            }
            return true;
        }
    }

    private static void markFailure(String key) {
        synchronized (recentFailures) {
            recentFailures.put(key, System.currentTimeMillis());
        }
    }

    private static String normalize(String gtin) {
        return gtin == null ? "" : gtin.trim();
    }

    private static void putMemory(String key, OffProductInfo value) {
        synchronized (memoryCache) {
            memoryCache.put(key, value);
        }
    }

    private static void post(Callback callback, OffProductInfo info) {
        mainHandler.post(() -> callback.onResult(info));
    }

    /**
     * Tries the GTIN as-is, then with leading zeros stripped.
     *
     * @return the product info, or null when OFF confirms the product is
     *         absent (a genuine miss — safe to negative-cache).
     * @throws IOException on transport failure or an unparseable OFF
     *         response: never negative-cached.
     */
    private static OffProductInfo fetchFromOff(String gtin) throws IOException {
        String stripped = gtin.replaceFirst("^0+", "");
        String[] candidates = stripped.isEmpty() || stripped.equals(gtin)
                ? new String[]{gtin}
                : new String[]{gtin, stripped};
        IOException transportError = null;
        for (String code : candidates) {
            String body;
            try {
                body = httpGetOrThrow(OFF_PRODUCT_URL
                        + URLEncoder.encode(code, "UTF-8") + ".json");
            } catch (IOException e) {
                transportError = e;
                continue;
            }
            try {
                JSONObject root = new JSONObject(body);
                if (root.optInt("status", 0) != 1) continue; // OFF: no such product
                JSONObject product = root.optJSONObject("product");
                if (product == null) continue;
                String img = product.optString("image_front_url", "");
                if (img.isEmpty()) img = product.optString("image_url", "");
                return new OffProductInfo(
                        img,
                        product.optString("product_name", ""),
                        product.optString("brands", ""),
                        product.optString("quantity", ""));
            } catch (Exception e) {
                transportError = new IOException("unparseable OFF response for " + code, e);
            }
        }
        if (transportError != null) throw transportError;
        return null; // OFF confirmed absent for every spelling: genuine miss
    }

    /**
     * GET with a single retry: OFF is user-visible (product images), so one
     * extra attempt on transport/5xx/429 failures is worth it. Client errors
     * other than 429 fail fast — retrying them never helps.
     */
    private static String httpGetOrThrow(String urlString) throws IOException {
        IOException lastError = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return httpGetOnce(urlString);
            } catch (IOException e) {
                lastError = e;
                String msg = e.getMessage();
                boolean clientError = msg != null
                        && msg.startsWith("OFF HTTP 4")
                        && !msg.startsWith("OFF HTTP 429");
                if (clientError || attempt == 2) break;
                try {
                    Thread.sleep(750);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", ie);
                }
            }
        }
        throw lastError;
    }

    private static String httpGetOnce(String urlString) throws IOException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlString).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestMethod("GET");
            c.setRequestProperty("User-Agent", USER_AGENT);
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("OFF HTTP " + code);
            }
            InputStream is = c.getInputStream();
            if (is == null) throw new IOException("OFF empty response");
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static File cacheFile(Context context, String key) {
        File dir = new File(context.getCacheDir(), "off_product_cache");
        if (!dir.exists()) dir.mkdirs();
        // GTINs are numeric; still sanitize defensively.
        return new File(dir, key.replaceAll("[^0-9A-Za-z]", "_") + ".json");
    }

    private static boolean diskHas(String key, Context context) {
        try {
            File f = cacheFile(context, key);
            if (!f.exists()) return false;
            if (System.currentTimeMillis() - f.lastModified() > TTL_MILLIS) {
                f.delete();
                return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static OffProductInfo readDiskCache(Context context, String key) {
        try {
            if (!diskHas(key, context)) return null;
            File f = cacheFile(context, key);
            StringBuilder sb = new StringBuilder();
            try (FileInputStream fis = new FileInputStream(f);
                 BufferedReader r = new BufferedReader(
                         new InputStreamReader(fis, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            String json = sb.toString().trim();
            // Empty file = negatively cached total miss.
            return json.isEmpty() ? null : OffProductInfo.fromJson(json);
        } catch (Exception e) {
            Log.w(TAG, "Disk cache read failed", e);
            return null;
        }
    }

    private static void writeDiskCache(Context context, String key,
                                       OffProductInfo info) {
        try (FileOutputStream fos = new FileOutputStream(cacheFile(context, key))) {
            // Null info = OFF-confirmed genuine miss: write empty file as
            // negative cache. Transport failures never reach here.
            String json = info == null ? "" : info.toJson().toString();
            fos.write(json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "Disk cache write failed", e);
        }
    }

    /** True when OFF has a friendlier name cached for this GTIN. */
    public static boolean hasCachedName(Context context, String gtin) {
        OffProductInfo info = getCached(context, gtin);
        return info != null && info.hasName();
    }

    /** Friendlier display name when cached, otherwise the fallback. */
    public static String displayNameOr(Context context, String gtin, String fallback) {
        OffProductInfo info = getCached(context, gtin);
        if (info != null && info.hasName()) return info.displayName();
        return fallback;
    }
}
