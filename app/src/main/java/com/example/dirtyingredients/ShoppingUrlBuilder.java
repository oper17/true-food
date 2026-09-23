package com.example.dirtyingredients;

import android.net.Uri;
import android.text.TextUtils;

import com.example.dirtyingredients.model.ProductResult;

/**
 * Builds Google Shopping URLs for the BUY flow.
 *
 * GTIN is Google's canonical product identifier: searching the shopping tab by
 * GTIN resolves the exact item (with price comparisons across merchants)
 * instead of a fuzzy text search. Falls back to the product name when USDA has
 * no GTIN for the item.
 */
public final class ShoppingUrlBuilder {
    private ShoppingUrlBuilder() {
    }

    /** Shopping URL for a product: GTIN when available, product name otherwise. */
    public static String buildProductUrl(ProductResult product) {
        if (product != null && !TextUtils.isEmpty(product.gtinUpc)) {
            return buildSearchUrl(product.gtinUpc.trim());
        }
        String name = product != null ? product.name : "";
        return buildSearchUrl(name);
    }

    /** Google Shopping tab search for a raw query (name or GTIN). */
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
}
