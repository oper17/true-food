package com.barelabel.app;

import android.net.Uri;
import android.text.TextUtils;

import com.barelabel.app.model.ProductResult;

import java.util.Locale;

/**
 * Builds Google Shopping URLs for the BUY flow.
 *
 * Searches the shopping tab by product name/description. (GTIN lookup was
 * tried first, but Google restricts raw barcode-number queries on the
 * shopping vertical, so name search is the reliable path.)
 */
public final class ShoppingUrlBuilder {
    private ShoppingUrlBuilder() {
    }

    /** Shopping URL for a product: name/description plus the label brand. */
    public static String buildProductUrl(ProductResult product) {
        return buildProductUrl(product, null);
    }

    /**
     * Raw shopping query for a product: OFF friendly naming when available,
     * else the USDA description plus the brand on the food label. The label
     * brand (brandName) is what shoppers recognize; OFF's brands fill the gap
     * when USDA has no brandName (OFF is keyed by GTIN and usually knows the
     * label brand); the brand owner is only a fallback because one owner
     * often holds many unrelated brands ("Post Consumer Brands" vs "Oreo O's").
     */
    public static String buildQuery(ProductResult product,
                                    com.barelabel.app.images.ProductImageResolver.OffProductInfo off) {
        if (off != null && off.hasName()) {
            String query = off.shoppingQuery();
            if (TextUtils.isEmpty(off.brands) && product != null) {
                // OFF knows the name but not the brand — backfill from USDA's
                // label brand so the query doesn't lose it.
                query = appendLabelBrand(query, product, null);
            }
            return query;
        }
        if (product == null) return "";
        return appendLabelBrand(cleanName(product.name), product,
                off != null ? off.brands : null);
    }

    /**
     * Appends the label brand to a query unless it's already in there.
     * Brand chain: USDA brandName -> OFF brands -> brandOwner -> product.brand.
     */
    private static String appendLabelBrand(String query, ProductResult product,
                                           String offBrands) {
        String labelBrand = !TextUtils.isEmpty(product.brandName) ? product.brandName
                : (!TextUtils.isEmpty(offBrands) ? offBrands
                        : (!TextUtils.isEmpty(product.brandOwner) ? product.brandOwner
                                : product.brand));
        String cleaned = cleanName(labelBrand);
        if (!cleaned.isEmpty()
                && !query.toLowerCase(Locale.US).contains(cleaned.toLowerCase(Locale.US))) {
            query = (query + " " + cleaned).trim();
        }
        return query;
    }

    /**
     * Shopping URL preferring Open Food Facts naming when available: its
     * product_name/brands/quantity form a much friendlier shopping query
     * than USDA's terse descriptions.
     */
    public static String buildProductUrl(ProductResult product,
                                         com.barelabel.app.images.ProductImageResolver.OffProductInfo off) {
        return buildSearchUrl(buildQuery(product, off));
    }

    /** Google Shopping tab search for a raw query (product name/description). */
    public static String buildSearchUrl(String query) {
        if (query == null || query.trim().isEmpty()) {
            return "https://www.google.com/search?tbm=shop";
        }
        return new Uri.Builder()
                .scheme("https")
                .authority("www.google.com")
                .path("search")
                .appendQueryParameter("tbm", "shop")
                .appendQueryParameter("q", query.trim())
                .build()
                .toString();
    }

    /**
     * Light cleanup of USDA descriptions for shopping search: drop
     * manufacturer parentheticals like "(Post Consumer Brands, LLC)",
     * strip corporate suffixes like ", LLC", and collapse whitespace.
     * Keeps the descriptive content intact.
     */
    private static String cleanName(String name) {
        if (TextUtils.isEmpty(name)) return "";
        String cleaned = name.replaceAll("\\([^)]*\\)", " ")
                .replaceAll("(?i),?\\s+\\b(llc|inc|ltd|co|corp|corporation|company)\\.?$", "")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned.isEmpty() ? name.trim() : cleaned;
    }
}
