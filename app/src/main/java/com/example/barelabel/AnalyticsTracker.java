package com.example.barelabel;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import com.google.firebase.analytics.FirebaseAnalytics;

/**
 * Central feature-level analytics. Every event goes through here so the
 * taxonomy stays in one place.
 *
 * Safe to call before Firebase is configured: if google-services.json hasn't
 * been added yet, events are dropped with a warning instead of crashing.
 * No raw query text or PII is logged — only lengths, counts, and booleans.
 */
public final class AnalyticsTracker {

    private static final String TAG = "AnalyticsTracker";

    private static FirebaseAnalytics firebaseAnalytics;

    private AnalyticsTracker() {
    }

    /** Call once from Application/Activity onCreate. */
    public static void init(Context context) {
        try {
            firebaseAnalytics =
                    FirebaseAnalytics.getInstance(context.getApplicationContext());
        } catch (Exception e) {
            Log.w(TAG, "Firebase Analytics unavailable (add google-services.json)", e);
            firebaseAnalytics = null;
        }
    }

    private static void log(String event, Bundle params) {
        if (firebaseAnalytics == null) return;
        try {
            firebaseAnalytics.logEvent(event, params);
        } catch (Exception e) {
            Log.w(TAG, "Failed to log event " + event, e);
        }
    }

    /** User pressed SEARCH / enter on the keyboard. */
    public static void searchPerformed(int queryLength) {
        Bundle b = new Bundle();
        b.putInt("query_length", queryLength);
        log("search_performed", b);
    }

    /** Barcode scan completed (success=false when cancelled or unreadable). */
    public static void barcodeScanned(boolean success) {
        Bundle b = new Bundle();
        b.putBoolean("success", success);
        log("barcode_scanned", b);
    }

    /** User tapped an autocomplete suggestion. */
    public static void suggestionTapped(int position, boolean unbranded) {
        Bundle b = new Bundle();
        b.putInt("position", position);
        b.putBoolean("unbranded", unbranded);
        log("suggestion_tapped", b);
    }

    /** Alternates card rendered after a fresh search (not on organic re-rank). */
    public static void alternatesViewed(int exactCount, int moreCount, boolean preferOrganic) {
        Bundle b = new Bundle();
        b.putInt("exact_count", exactCount);
        b.putInt("more_count", moreCount);
        b.putBoolean("prefer_organic", preferOrganic);
        log("alternates_viewed", b);
    }

    /** User opened an alternate's ingredient dialog. */
    public static void alternateIngredientsViewed() {
        log("alternate_ingredients_viewed", new Bundle());
    }

    /**
     * User tapped a BUY! button.
     * source: "primary_product", "search_box", or "alternate".
     */
    public static void buyTapped(String source) {
        Bundle b = new Bundle();
        b.putString("source", source);
        log("buy_tapped", b);
    }

    /** User toggled the Prefer Organic button. */
    public static void preferOrganicToggled(boolean enabled) {
        Bundle b = new Bundle();
        b.putBoolean("enabled", enabled);
        log("prefer_organic_toggled", b);
    }

    /** User toggled a category filter chip. */
    public static void categoryToggled(String category, boolean enabled) {
        Bundle b = new Bundle();
        b.putString("category", category);
        b.putBoolean("enabled", enabled);
        log("category_toggled", b);
    }

    /** User opened the scan-history screen. */
    public static void historyOpened() {
        log("history_opened", new Bundle());
    }

    /** User opened the Compare tab (fires once per tab selection). */
    public static void compareTabOpened() {
        log("compare_tab_opened", new Bundle());
    }

    /**
     * User toggled a compare checkbox.
     * source: "verdict_card", "alternate", or "history".
     */
    public static void compareCheckboxToggled(boolean checked, String source) {
        Bundle b = new Bundle();
        b.putBoolean("checked", checked);
        b.putString("source", source);
        log("compare_checkbox_toggled", b);
    }

    /**
     * User saved a product to history.
     * source: "alternate" or "compare".
     */
    public static void productSaved(String source) {
        Bundle b = new Bundle();
        b.putString("source", source);
        log("product_saved", b);
    }
}
