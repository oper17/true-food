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
 * Resolves a product image URL for a GTIN via Open Food Facts.
 *
 * Lookup chain: in-memory cache -> 7-day disk cache -> OFF network request
 * (tries the GTIN as-is, then with leading zeros stripped). Misses are
 * negatively cached so we don't re-query barcodes that have no image.
 *
 * Results are delivered on the main thread. A null URL means "no image".
 */
public class ProductImageResolver {

    private static final String TAG = "ProductImageResolver";
    private static final String OFF_PRODUCT_URL =
            "https://world.openfoodfacts.org/api/v2/product/";
    private static final String USER_AGENT =
            "BareLabel/1.0 (Android food ingredient screening app)";
    private static final long TTL_MILLIS = TimeUnit.DAYS.toMillis(7);
    private static final int MEMORY_CACHE_SIZE = 200;

    public interface Callback {
        /** Called on the main thread; imageUrl is null when no image exists. */
        void onResult(String imageUrl);
    }

    private static final ExecutorService executor =
            Executors.newFixedThreadPool(2);
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Synchronized LRU memory cache: gtin -> image URL ("" = known miss).
    private static final Map<String, String> memoryCache =
            new LinkedHashMap<String, String>(MEMORY_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> e) {
                    return size() > MEMORY_CACHE_SIZE;
                }
            };

    private ProductImageResolver() {
    }

    public static void resolveImageUrl(Context context, String gtin, Callback callback) {
        final Context appContext = context.getApplicationContext();
        final String key = normalize(gtin);
        if (key.isEmpty()) {
            post(callback, null);
            return;
        }
        synchronized (memoryCache) {
            if (memoryCache.containsKey(key)) {
                post(callback, emptyToNull(memoryCache.get(key)));
                return;
            }
        }
        executor.execute(() -> {
            String cached = readDiskCache(appContext, key);
            if (cached != null) {
                putMemory(key, cached);
                post(callback, emptyToNull(cached));
                return;
            }
            String url = fetchFromOff(key);
            String store = url == null ? "" : url; // negative-cache misses
            writeDiskCache(appContext, key, store);
            putMemory(key, store);
            post(callback, url);
        });
    }

    private static String normalize(String gtin) {
        return gtin == null ? "" : gtin.trim();
    }

    private static String emptyToNull(String v) {
        return (v == null || v.isEmpty()) ? null : v;
    }

    private static void putMemory(String key, String value) {
        synchronized (memoryCache) {
            memoryCache.put(key, value);
        }
    }

    private static void post(Callback callback, String url) {
        mainHandler.post(() -> callback.onResult(url));
    }

    /** Tries the GTIN as-is, then with leading zeros stripped. */
    private static String fetchFromOff(String gtin) {
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
                if (!img.isEmpty()) return img;
                // Product exists but has no image: definitive miss.
                return null;
            } catch (Exception e) {
                Log.w(TAG, "OFF lookup failed for " + code, e);
            }
        }
        return null;
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
        File dir = new File(context.getCacheDir(), "off_image_cache");
        if (!dir.exists()) dir.mkdirs();
        // GTINs are numeric; still sanitize defensively.
        return new File(dir, key.replaceAll("[^0-9A-Za-z]", "_") + ".txt");
    }

    private static String readDiskCache(Context context, String key) {
        try {
            File f = cacheFile(context, key);
            if (!f.exists()) return null;
            if (System.currentTimeMillis() - f.lastModified() > TTL_MILLIS) {
                f.delete();
                return null;
            }
            try (FileInputStream fis = new FileInputStream(f);
                 BufferedReader r = new BufferedReader(
                         new InputStreamReader(fis, StandardCharsets.UTF_8))) {
                return r.readLine(); // single-line URL ("" = known miss)
            }
        } catch (Exception e) {
            Log.w(TAG, "Disk cache read failed", e);
            return null;
        }
    }

    private static void writeDiskCache(Context context, String key, String value) {
        try (FileOutputStream fos = new FileOutputStream(cacheFile(context, key))) {
            fos.write((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "Disk cache write failed", e);
        }
    }
}
