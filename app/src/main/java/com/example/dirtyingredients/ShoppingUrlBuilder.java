package com.example.dirtyingredients;

import android.net.Uri;
import android.text.TextUtils;

import com.example.dirtyingredients.model.ProductResult;

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

    /** Shopping URL for a product, by its name/description. */
    public static String buildProductUrl(ProductResult product) {
        String name = product != null ? product.name : "";
        return buildSearchUrl(cleanName(name));
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
     * manufacturer parentheticals like "(Post Consumer Brands, LLC)" and
     * collapse whitespace. Keeps the descriptive content intact.
     */
    private static String cleanName(String name) {
        if (TextUtils.isEmpty(name)) return "";
        String cleaned = name.replaceAll("\\([^)]*\\)", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned.isEmpty() ? name.trim() : cleaned;
    }
}
