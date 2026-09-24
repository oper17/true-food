package com.barelabel.app.affiliate;

/**
 * One retailer in the affiliate program: builds tagged retailer URLs.
 *
 * Implementations are pure URL builders — no network, no state — so they
 * stay unit-testable. Affiliate tags come from {@link AffiliateConfig};
 * provider enablement is decided by {@link AffiliateManager}.
 */
public interface AffiliateProvider {

    /** Stable id used in affiliate_mappings.json, e.g. "amazon". */
    String retailerId();

    /** User-facing name, e.g. "Amazon". */
    String displayName();

    /** Tagged search URL for a free-text query (fallback when no SKU mapping). */
    String searchUrl(String query);

    /**
     * Apply this retailer's affiliate tagging to a direct product URL from
     * the mapping file. Returns the URL unchanged when tagging is not
     * supported client-side (see Walmart provider notes).
     */
    String directUrl(String rawUrl);

    /**
     * Whether curated mapping entries may target this retailer.
     * Search-only providers (no affiliate program) return false: they are
     * skipped when resolving direct mappings and never appear in the
     * multi-retailer picker.
     */
    default boolean supportsDirectLinks() {
        return true;
    }
}
