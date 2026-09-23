package com.example.barelabel;

import java.util.List;

/**
 * Generic contract for fetching auto-complete food suggestions.
 * Implementations can fetch from USDA API, local database, or cache.
 */
public interface SuggestionProvider {
    List<String> fetchSuggestions(String query) throws Exception;

    /** Whether a returned suggestion came from the offline unbranded dictionary. */
    default boolean isUnbrandedSuggestion(String suggestion) {
        return false;
    }
}
