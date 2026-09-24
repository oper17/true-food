package com.barelabel.app.affiliate;

import android.net.Uri;

/** Amazon Associates provider: tag-carrying search and product URLs. */
public final class AmazonAffiliateProvider implements AffiliateProvider {

    @Override
    public String retailerId() {
        return "amazon";
    }

    @Override
    public String displayName() {
        return "Amazon";
    }

    @Override
    public String searchUrl(String query) {
        Uri.Builder b = new Uri.Builder()
                .scheme("https")
                .authority("www.amazon.com")
                .path("s")
                .appendQueryParameter("k", query == null ? "" : query);
        String tag = AffiliateConfig.tagFor(retailerId());
        if (!tag.isEmpty()) b.appendQueryParameter("tag", tag);
        return b.build().toString();
    }

    @Override
    public String directUrl(String rawUrl) {
        if (rawUrl == null) return "";
        String tag = AffiliateConfig.tagFor(retailerId());
        if (tag.isEmpty() || !rawUrl.contains("amazon.")) return rawUrl;
        if (rawUrl.contains("tag=")) return rawUrl; // already tagged
        return rawUrl + (rawUrl.contains("?") ? "&" : "?") + "tag="
                + Uri.encode(tag);
    }
}
