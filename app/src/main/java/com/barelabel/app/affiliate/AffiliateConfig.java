package com.barelabel.app.affiliate;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client-side affiliate configuration.
 *
 * Everything here is a local default. Values are read through static
 * accessors (never inlined at call sites) so they can later be backed by
 * Firebase Remote Config without touching providers or UI: add a
 * Remote Config fetch on app start and have the accessors prefer the
 * fetched values, falling back to the defaults below when offline.
 */
public final class AffiliateConfig {
    private AffiliateConfig() {
    }

    /**
     * FTC disclosure shown near every affiliate Buy surface (button captions
     * and the retailer picker). Keep it short, plain, and adjacent to the
     * links — a footnote alone is not "clear and conspicuous".
     */
    public static final String DISCLOSURE_TEXT =
            "We may earn a commission when you buy through links on this page.";

    private static final Map<String, String> DEFAULT_TAGS;
    static {
        Map<String, String> tags = new HashMap<>();
        tags.put("amazon", "barelabel-20"); // TODO: replace with the real Associates tag
        DEFAULT_TAGS = Collections.unmodifiableMap(tags);
    }

    /** Remote-config overrides applied at runtime; empty until wired up. */
    private static final Map<String, String> TAG_OVERRIDES = new HashMap<>();

    /** Affiliate tag for a retailer, preferring a remote override. */
    public static String tagFor(String retailerId) {
        String override = TAG_OVERRIDES.get(retailerId);
        if (override != null && !override.isEmpty()) return override;
        String def = DEFAULT_TAGS.get(retailerId);
        return def == null ? "" : def;
    }

    /** Called by the future Remote Config integration; no-op until then. */
    public static void setTagOverrides(Map<String, String> overrides) {
        TAG_OVERRIDES.clear();
        if (overrides != null) TAG_OVERRIDES.putAll(overrides);
    }

    /**
     * Retailers used for the search-URL fallback: just the user's preferred
     * retailer, so Buy stays one tap. Curated multi-retailer direct mappings
     * always show the picker regardless of this setting.
     */
    public static List<String> enabledProviders(Context context) {
        return Collections.singletonList(getPreferredRetailer(context));
    }

    private static final String PREFS = "affiliate_prefs";
    private static final String KEY_PREFERRED_RETAILER = "preferred_retailer";

    /**
     * Remote mapping file for in-place updates. The repo is public, so
     * reads need no auth — the app fetches this with a plain HTTPS GET.
     * Updating the file (and bumping "version") refreshes every installed
     * app within a day, no Play Store update required.
     */
    public static final String REMOTE_MAPPINGS_URL =
            "https://raw.githubusercontent.com/slplakshmipriya/true-food"
                    + "/main/app/src/main/assets/affiliate_mappings.json";

    /** Minimum time between remote mapping checks. */
    public static final long REMOTE_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000;

    /** User's preferred retailer for Buy links; "amazon" until changed. */
    public static String getPreferredRetailer(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
                PREFS, Context.MODE_PRIVATE);
        String id = prefs.getString(KEY_PREFERRED_RETAILER, "amazon");
        return id == null || id.isEmpty() ? "amazon" : id;
    }

    public static void setPreferredRetailer(Context context, String retailerId) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PREFERRED_RETAILER, retailerId)
                .apply();
    }
}
