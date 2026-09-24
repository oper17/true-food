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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
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
 * (tries the GTIN as-is, then with leading zeros stripped). Results are
 * cached as JSON, including negative results, so a barcode is only ever
 * looked up once per TTL window.
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
        OffProductInfo mem;
        boolean hit;
        synchronized (memoryCache) {
            hit = memoryCache.containsKey(key);
            mem = hit ? memoryCache.get(key) : null;
        }
        if (hit) {
            post(callback, mem);
            return;
        }
        executor.execute(() -> {
            OffProductInfo cached = readDiskCache(appContext, key);
            if (cached != null || diskHas(key, appContext)) {
                putMemory(key, cached);
                post(callback, cached);
                return;
            }
            OffProductInfo fetched = fetchFromOff(key);
            writeDiskCache(appContext, key, fetched);
            putMemory(key, fetched);
            post(callback, fetched);
        });
    }

    /**
     * Synchronous cache-only lookup (memory, then disk). Never hits the
     * network. Returns null when nothing is cached yet.
     */
    public static OffProductInfo getCached(Context context, String gtin) {
        String key = normalize(gtin);
        if (key.isEmpty()) return null;
        synchronized (memoryCache) {
            if (memoryContains(key)) return memoryCache.get(key);
        }
        OffProductInfo disk = readDiskCache(context.getApplicationContext(), key);
        if (disk != null) putMemory(key, disk);
        return disk;
    }

    private static boolean memoryContains(String key) {
        synchronized (memoryCache) {
            return memoryCache.containsKey(key);
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

    /** Tries the GTIN as-is, then with leading zeros stripped. */
    private static OffProductInfo fetchFromOff(String gtin) {
        String stripped = gtin.replaceFirst("^0+", "");
        String[] candidates = stripped.isEmpty() || stripped.equals(gtin)
                ? new String[]{gtin}
                : new String[]{gtin, stripped};
        for (String code : candidates) {
            try {
                String body = httpGet(OFF_PRODUCT_URL
                        + URLEncoder.encode(code, "UTF-8") + ".json");
                if (body == null) continue;
                JSONObject root = new JSONObject(body);
                if (root.optInt("status", 0) != 1) continue;
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
                Log.w(TAG, "OFF lookup failed for " + code, e);
            }
        }
        return null; // total miss: not in OFF or unreachable
    }

    private static String httpGet(String urlString) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlString).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestMethod("GET");
            c.setRequestProperty("User-Agent", USER_AGENT);
            int code = c.getResponseCode();
            InputStream is = (code >= 200 && code < 300)
                    ? c.getInputStream() : c.getErrorStream();
            if (is == null) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "HTTP GET failed: " + urlString, e);
            return null;
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
            // Null info = total miss: write empty file as negative cache.
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
