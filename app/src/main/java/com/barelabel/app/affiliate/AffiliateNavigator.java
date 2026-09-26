package com.barelabel.app.affiliate;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;

import androidx.browser.customtabs.CustomTabColorSchemeParams;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.core.content.ContextCompat;

import com.barelabel.app.AnalyticsTracker;
import com.barelabel.app.R;
import com.barelabel.app.ShoppingUrlBuilder;
import com.barelabel.app.images.ProductImageResolver;
import com.barelabel.app.model.ProductResult;

import java.util.List;

/**
 * Owns the Buy tap flow: resolve affiliate links, open them in Custom Tabs
 * (never a WebView), and show the FTC disclosure.
 *
 * One link  -> opened directly (Buy stays one tap).
 * Many links -> retailer picker sheet; the disclosure sits in the dialog
 *               message, adjacent to the links.
 * No links  -> falls back to a Google Shopping search (no affiliate tag,
 *               kept only as a safety net; default config always yields one).
 */
public final class AffiliateNavigator {

    private static final int C_GRAY_900 = android.graphics.Color.parseColor("#111827");
    private static final int C_GRAY_500 = android.graphics.Color.parseColor("#6B7280");
    private AffiliateNavigator() {
    }

    /** Buy flow for a product; source: "primary_product", "alternate", "compare". */
    public static void openBuy(Activity activity, ProductResult product,
                               ProductImageResolver.OffProductInfo off,
                               String source) {
        String name = displayNameFor(product, off);
        String brand = brandFor(product, off);
        String query = ShoppingUrlBuilder.buildQuery(product, off);
        List<AffiliateLink> links =
                AffiliateManager.get(activity).linksFor(name, brand, query);
        AnalyticsTracker.affiliateImpression(source, links);
        if (links.isEmpty()) {
            openInCustomTab(activity,
                    ShoppingUrlBuilder.buildSearchUrl(query));
            return;
        }
        if (links.size() == 1) {
            openLink(activity, links.get(0), source);
        } else {
            showRetailerPicker(activity, links, source);
        }
    }

    /** Buy flow for a raw search-box query; source: "search_box". */
    public static void openBuyForQuery(Activity activity, String query,
                                      String source) {
        List<AffiliateLink> links = AffiliateManager.get(activity)
                .linksFor(query, "", query);
        AnalyticsTracker.affiliateImpression(source, links);
        if (links.size() == 1) {
            openLink(activity, links.get(0), source);
        } else if (!links.isEmpty()) {
            showRetailerPicker(activity, links, source);
        } else {
            openInCustomTab(activity, ShoppingUrlBuilder.buildSearchUrl(query));
        }
    }

    /**
     * Whether the item itself has curated affiliate links (a direct mapping
     * matched). The FTC disclosure is shown only in this case — not for
     * plain search-fallback links.
     */
    public static boolean hasAffiliateLinks(android.content.Context context,
                                           ProductResult product,
                                           ProductImageResolver.OffProductInfo off) {
        return AffiliateManager.get(context).hasDirectMapping(
                displayNameFor(product, off), brandFor(product, off));
    }

    /** Name/brand overload for surfaces without a ProductResult (compare). */
    public static boolean hasAffiliateLinks(android.content.Context context,
                                           String name, String brand) {
        return AffiliateManager.get(context).hasDirectMapping(name, brand);
    }

    private static String displayNameFor(ProductResult product,
                                         ProductImageResolver.OffProductInfo off) {
        if (off != null && off.hasName()) return off.displayName();
        return product == null || product.name == null ? "" : product.name;
    }

    private static String brandFor(ProductResult product,
                                   ProductImageResolver.OffProductInfo off) {
        if (off != null && !TextUtils.isEmpty(off.brands)) return off.brands;
        return brandOf(product);
    }

    private static void openLink(Activity activity, AffiliateLink link,
                                 String source) {
        AnalyticsTracker.affiliateTap(source, link.retailerId, link.direct);
        openInCustomTab(activity, link.url);
    }

    /**
     * Retailer picker. Built as a custom view (title + disclosure + one
     * button per retailer) because AlertDialog silently drops the item list
     * when setMessage() and setItems() are combined.
     */
    private static void showRetailerPicker(Activity activity,
                                           List<AffiliateLink> links,
                                           String source) {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(activity);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = dp(activity, 20);
        layout.setPadding(pad, dp(activity, 16), pad, dp(activity, 8));

        android.widget.TextView title = new android.widget.TextView(activity);
        title.setText("Choose a retailer");
        title.setTextSize(18f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(android.graphics.C_GRAY_900);
        layout.addView(title);

        // FTC disclosure, adjacent to the retailer links.
        android.widget.TextView disclosure = new android.widget.TextView(activity);
        disclosure.setText(AffiliateConfig.DISCLOSURE_TEXT);
        disclosure.setTextSize(13f);
        disclosure.setTextColor(android.graphics.C_GRAY_500);
        android.widget.LinearLayout.LayoutParams disclosureParams =
                new android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        disclosureParams.topMargin = dp(activity, 8);
        disclosureParams.bottomMargin = dp(activity, 8);
        layout.addView(disclosure, disclosureParams);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(layout)
                .create();

        for (AffiliateLink link : links) {
            android.widget.Button retailerButton =
                    new android.widget.Button(activity);
            retailerButton.setAllCaps(false);
            retailerButton.setText(link.displayName
                    + (link.direct ? " \u00b7 Direct link" : " \u00b7 Search"));
            retailerButton.setOnClickListener(v -> {
                dialog.dismiss();
                openLink(activity, link, source);
            });
            android.widget.LinearLayout.LayoutParams buttonParams =
                    new android.widget.LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            buttonParams.topMargin = dp(activity, 4);
            layout.addView(retailerButton, buttonParams);
        }

        android.widget.Button cancelButton = new android.widget.Button(activity,
                null, android.R.attr.borderlessButtonStyle);
        cancelButton.setAllCaps(false);
        cancelButton.setText("Cancel");
        cancelButton.setOnClickListener(v -> dialog.dismiss());
        android.widget.LinearLayout.LayoutParams cancelParams =
                new android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        cancelParams.gravity = android.view.Gravity.END;
        cancelParams.topMargin = dp(activity, 8);
        layout.addView(cancelButton, cancelParams);

        dialog.show();
    }

    private static int dp(Activity activity, int dp) {
        return Math.round(dp * activity.getResources().getDisplayMetrics().density);
    }

    private static void openInCustomTab(Activity activity, String url) {
        CustomTabColorSchemeParams colorParams =
                new CustomTabColorSchemeParams.Builder()
                        .setToolbarColor(ContextCompat.getColor(activity,
                                R.color.cream))
                        .build();
        CustomTabsIntent customTabsIntent = new CustomTabsIntent.Builder()
                .setDefaultColorSchemeParams(colorParams)
                .setShowTitle(true)
                // Keep the URL bar visible: these are affiliate shopping
                // links, and the user should always see where they are.
                .setUrlBarHidingEnabled(false)
                .build();
        try {
            customTabsIntent.launchUrl(activity, Uri.parse(url));
        } catch (Exception e) {
            activity.startActivity(
                    new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        }
    }

    private static String brandOf(ProductResult product) {
        if (product == null) return "";
        // product.brand is the food-label brand (brandName-first); fall back
        // to the raw fields for results built before that change.
        if (!TextUtils.isEmpty(product.brand)) return product.brand;
        if (!TextUtils.isEmpty(product.brandName)) return product.brandName;
        return product.brandOwner == null ? "" : product.brandOwner;
    }
}
