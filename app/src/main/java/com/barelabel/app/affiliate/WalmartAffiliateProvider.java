package com.barelabel.app.affiliate;

import android.net.Uri;

/**
 * Walmart provider.
 *
 * NOTE: real Walmart affiliate links must be generated through their
 * affiliate API (signed, per-click URLs), which needs a backend — see the
 * middleware discussion. Client-side we can only build plain search/product
 * URLs, so directUrl() passes the mapping URL through untagged and the
 * search fallback carries no tag. Do not represent untagged Walmart links
 * as commission-earning; enable this provider only with real tagged URLs.
 */
public final class WalmartAffiliateProvider implements AffiliateProvider {

    @Override
    public String retailerId() {
        return "walmart";
    }

    @Override
    public String displayName() {
        return "Walmart";
    }

    @Override
    public String searchUrl(String query) {
        return new Uri.Builder()
                .scheme("https")
                .authority("www.walmart.com")
                .path("search")
                .appendQueryParameter("q", query == null ? "" : query)
                .build()
                .toString();
    }

    @Override
    public String directUrl(String rawUrl) {
        return rawUrl == null ? "" : rawUrl;
    }
}
