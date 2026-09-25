package com.barelabel.app.affiliate;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Registry + resolver for affiliate links. Client-side only.
 *
 * The mapping file is versioned and resolved from two sources:
 *   1. The bundled asset (ships with the APK).
 *   2. A cached remote copy, refreshed in the background from a public
 *      raw-GitHub URL (no auth needed). A remote copy is used only when
 *      its detached RSA signature verifies against the key in
 *      {@link AffiliateConfig} AND its "version" is newer than the loaded
 *      one; every failure mode falls back to whatever is already loaded.
 *      Only https:// mapping URLs are ever opened.
 *
 * Resolution order per product:
 *   1. Curated direct retailer URLs whose name/brand match the product.
 *   2. Tagged search URLs from each enabled provider (fallback).
 *
 * Unknown retailer ids in the file are skipped.
 */
public final class AffiliateManager {

    private static final String TAG = "AffiliateManager";
    private static final String ASSET_FILE = "affiliate_mappings.json";
    private static final String CACHE_FILE = "affiliate_mappings_remote.json";
    private static final String CACHE_SIG_FILE = "affiliate_mappings_remote.json.sig";
    private static final String PREFS = "affiliate_prefs";
    private static final String KEY_LAST_CHECK = "remote_last_check";

    private static AffiliateManager instance;

    public static synchronized AffiliateManager get(Context context) {
        if (instance == null) {
            instance = new AffiliateManager(context.getApplicationContext());
        }
        return instance;
    }

    /**
     * Fire-and-forget remote refresh. Safe to call on the main thread —
     * all network and parsing happens on a background thread, throttled
     * to once per {@link AffiliateConfig#REMOTE_CHECK_INTERVAL_MS}.
     * Failures are silent: the app keeps its current mappings.
     */
    public static void checkForUpdates(Context context) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                doCheckForUpdates(app);
            } catch (Exception e) {
                Log.w(TAG, "remote mappings check failed; keeping current", e);
            }
        }, "affiliate-remote-check").start();
    }

    private static void doCheckForUpdates(Context app) throws Exception {
        SharedPreferences prefs =
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (now - prefs.getLong(KEY_LAST_CHECK, 0)
                < AffiliateConfig.REMOTE_CHECK_INTERVAL_MS) {
            return;
        }
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply();

        // Fail closed: the file is applied only if its detached RSA
        // signature verifies. A missing/bad signature (e.g. repo compromise
        // or MITM) keeps the current mappings instead of applying evil URLs.
        byte[] jsonBytes = fetchBytes(
                AffiliateConfig.REMOTE_MAPPINGS_URL + "?t=" + now / 1000);
        if (jsonBytes == null) return;
        byte[] sigBytes = fetchBytes(
                AffiliateConfig.REMOTE_MAPPINGS_SIG_URL + "?t=" + now / 1000);
        if (sigBytes == null || !verifySignature(jsonBytes, sigBytes)) {
            Log.w(TAG, "remote mappings signature missing/invalid; keeping current");
            return;
        }

        ParsedMappings parsed =
                parseMappingsJson(new String(jsonBytes, StandardCharsets.UTF_8));
        Log.i(TAG, "remote mappings signature verified (v" + parsed.version + ")");
        AffiliateManager mgr = get(app);
        if (parsed.version > mgr.mappingVersion && !parsed.entries.isEmpty()) {
            writeCacheFile(app, CACHE_FILE, jsonBytes);
            writeCacheFile(app, CACHE_SIG_FILE, sigBytes);
            mgr.applyParsed(parsed);
            Log.i(TAG, "applied verified remote affiliate mappings v"
                    + parsed.version);
        }
    }

    /** GET bytes; null on any failure (offline, non-200, timeout). */
    private static byte[] fetchBytes(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setUseCaches(false); // raw.githubusercontent.com caches aggressively
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setRequestProperty("Accept",
                    url.endsWith(".sig") ? "application/octet-stream"
                            : "application/json");
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            try (InputStream in = conn.getInputStream()) {
                return readFully(in);
            }
        } catch (Exception e) {
            Log.w(TAG, "fetch failed: " + url, e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** RSA-SHA256 verification of the mappings file against the embedded key. */
    private static boolean verifySignature(byte[] data, byte[] signature) {
        try {
            byte[] der = Base64.decode(
                    AffiliateConfig.MAPPINGS_PUBLIC_KEY_B64, Base64.DEFAULT);
            PublicKey key = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(der));
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(key);
            sig.update(data);
            return sig.verify(signature);
        } catch (Exception e) {
            Log.w(TAG, "mappings signature verification error", e);
            return false;
        }
    }

    private final Map<String, AffiliateProvider> providers = new HashMap<>();
    private final Context appContext;
    // Volatile references: the background refresh swaps the whole list
    // atomically, so readers never see a half-updated mapping set.
    private volatile List<MappingEntry> mappings = new ArrayList<>();
    private volatile int mappingVersion;

    private AffiliateManager(Context context) {
        this.appContext = context.getApplicationContext();
        register(new AmazonAffiliateProvider());
        register(new WalmartAffiliateProvider());
        register(new GoogleShoppingProvider());
        loadBundledWithRemoteOverride();
    }

    private void register(AffiliateProvider provider) {
        providers.put(provider.retailerId(), provider);
    }

    /** All registered providers, for the preferred-retailer setting UI. */
    public List<AffiliateProvider> allProviders() {
        return new ArrayList<>(providers.values());
    }

    /**
     * True when a curated direct mapping matches this product — i.e. the
     * item (not just the query) has affiliate links. Drives the FTC
     * disclosure: it is shown only for mapped items, not for plain
     * search-fallback links.
     */
    public boolean hasDirectMapping(String productName, String brand) {
        return findMapping(productName, brand) != null;
    }

    private MappingEntry findMapping(String productName, String brand) {
        String normName = normalize(productName);
        String normBrand = normalize(brand);
        for (MappingEntry entry : mappings) {
            if (!normName.contains(entry.nameContains)) continue;
            if (entry.brandContains != null
                    && !normBrand.contains(entry.brandContains)) continue;
            return entry;
        }
        return null;
    }
    /**
     * Resolve buy links for a product. Never returns an empty list as long
     * as at least one provider is enabled: falls back to provider search.
     */
    public List<AffiliateLink> linksFor(String productName, String brand,
                                       String query) {
        List<AffiliateLink> direct = new ArrayList<>();
        MappingEntry entry = findMapping(productName, brand);
        if (entry != null) {
            for (MappingEntry.RetailerMapping rm : entry.retailers) {
                AffiliateProvider provider = providers.get(rm.retailerId);
                if (provider == null || !provider.supportsDirectLinks()) continue;
                String url = provider.directUrl(rm.url);
                if (!isHttpsUrl(url)) {
                    Log.w(TAG, "dropping non-https mapping URL for "
                            + rm.retailerId);
                    continue;
                }
                direct.add(new AffiliateLink(rm.retailerId,
                        provider.displayName(), url, true));
            }
        }
        if (!direct.isEmpty()) return direct;

        List<AffiliateLink> fallback = new ArrayList<>();
        for (String retailerId : AffiliateConfig.enabledProviders(appContext)) {
            AffiliateProvider provider = providers.get(retailerId);
            if (provider == null) continue;
            String url = provider.searchUrl(query == null ? "" : query);
            fallback.add(new AffiliateLink(retailerId, provider.displayName(),
                    url, false));
        }
        return fallback;
    }

    /** Only https mapping URLs are ever opened; everything else is dropped. */
    private static boolean isHttpsUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.US);
        return lower.startsWith("https://");
    }

    /** Version of the loaded mapping file; 0 when the file is missing. */
    public int getMappingVersion() {
        return mappingVersion;
    }

    /** Atomically swap in a freshly parsed mapping set. */
    private void applyParsed(ParsedMappings parsed) {
        mappings = parsed.entries;
        mappingVersion = parsed.version;
    }

    /** Bundled asset first, then the cached remote copy if it is newer. */
    private void loadBundledWithRemoteOverride() {
        applyParsed(loadBundled());
        ParsedMappings cached = loadCachedRemote();
        if (cached != null && cached.version > mappingVersion) {
            applyParsed(cached);
        }
    }

    private ParsedMappings loadBundled() {
        try {
            byte[] bytes = readFully(appContext.getAssets().open(ASSET_FILE));
            return parseMappingsJson(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "bundled affiliate_mappings.json missing/unreadable", e);
            return new ParsedMappings();
        }
    }

    /**
     * Cached remote copy, used only if its stored signature still verifies.
     * A cache written by the pre-signature app version has no .sig and is
     * ignored — the next background refresh re-fetches signed copies.
     */
    private ParsedMappings loadCachedRemote() {
        File dir = appContext.getFilesDir();
        File f = new File(dir, CACHE_FILE);
        File sig = new File(dir, CACHE_SIG_FILE);
        if (!f.exists() || !sig.exists()) return null;
        try (FileInputStream in = new FileInputStream(f);
             FileInputStream sigIn = new FileInputStream(sig)) {
            byte[] bytes = readFully(in);
            if (!verifySignature(bytes, readFully(sigIn))) {
                Log.w(TAG, "cached remote mappings signature invalid; ignoring");
                return null;
            }
            return parseMappingsJson(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "cached remote mappings unreadable; ignoring", e);
            return null;
        }
    }

    /** Write-temp-then-rename so a crash mid-write never corrupts the cache. */
    private static void writeCacheFile(Context app, String name, byte[] data)
            throws IOException {
        File dir = app.getFilesDir();
        File tmp = new File(dir, name + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
        }
        if (!tmp.renameTo(new File(dir, name))) {
            throw new IOException("failed to publish " + name);
        }
    }

    private static byte[] readFully(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** Parsed form of one mapping file; version 0 / empty when unreadable. */
    private static final class ParsedMappings {
        int version;
        final List<MappingEntry> entries = new ArrayList<>();
    }

    private static ParsedMappings parseMappingsJson(String json) throws Exception {
        ParsedMappings parsed = new ParsedMappings();
        JSONObject root = new JSONObject(json);
        parsed.version = root.optInt("version", 0);
        JSONArray arr = root.optJSONArray("mappings");
        if (arr == null) return parsed;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            MappingEntry entry = new MappingEntry();
            entry.nameContains = normalize(o.optString("name_contains", ""));
            if (entry.nameContains.isEmpty()) continue;
            String brand = o.optString("brand_contains", null);
            entry.brandContains = brand == null || brand.isEmpty()
                    ? null : normalize(brand);
            JSONArray rets = o.optJSONArray("retailers");
            if (rets == null) continue;
            for (int j = 0; j < rets.length(); j++) {
                JSONObject r = rets.optJSONObject(j);
                if (r == null) continue;
                MappingEntry.RetailerMapping rm =
                        new MappingEntry.RetailerMapping();
                rm.retailerId = r.optString("retailer", "").toLowerCase(Locale.US);
                rm.url = r.optString("url", "");
                if (!rm.retailerId.isEmpty() && !rm.url.isEmpty()) {
                    entry.retailers.add(rm);
                }
            }
            if (!entry.retailers.isEmpty()) parsed.entries.add(entry);
        }
        return parsed;
    }

    /** Lowercase, strip punctuation, collapse whitespace for fuzzy matching. */
    static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static final class MappingEntry {
        String nameContains;
        String brandContains; // optional
        final List<RetailerMapping> retailers = new ArrayList<>();

        static final class RetailerMapping {
            String retailerId;
            String url;
        }
    }
}
