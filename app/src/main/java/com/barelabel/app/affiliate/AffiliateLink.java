package com.barelabel.app.affiliate;

/**
 * A single buyable link resolved for a product: either a direct retailer
 * product URL (from the curated mapping file) or a tagged retailer search
 * URL (fallback). Rendered by {@link AffiliateNavigator}.
 */
public final class AffiliateLink {
    public final String retailerId;
    public final String displayName;
    public final String url;
    public final boolean direct;

    public AffiliateLink(String retailerId, String displayName,
                         String url, boolean direct) {
        this.retailerId = retailerId;
        this.displayName = displayName;
        this.url = url;
        this.direct = direct;
    }
}
