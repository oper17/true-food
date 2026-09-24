package com.barelabel.app.affiliate;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * Google Shopping search. Search-only provider: Google carries no
 * affiliate tag here and this provider is never a curated direct-mapping
 * target ({@link #supportsDirectLinks()} is false), so it never appears
 * in the multi-retailer picker. It is only used as the preferred-retailer
 * search fallback for unmapped products.
 */
public class GoogleShoppingProvider implements AffiliateProvider {

    @Override
    public String retailerId() {
        return "google";
    }

    @Override
    public String displayName() {
        return "Google Shopping";
    }

    @Override
    public String searchUrl(String query) {
        return "https://www.google.com/search?tbm=shop&q=" + encode(query);
    }

    @Override
    public String directUrl(String url) {
        return url == null ? "" : url;
    }

    @Override
    public boolean supportsDirectLinks() {
        return false;
    }

    private static String encode(String query) {
        if (query == null) return "";
        try {
            return URLEncoder.encode(query, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return query;
        }
    }
}
