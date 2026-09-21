package com.example.dirtyingredients;

import java.util.List;

/**
 * Generic contract for fetching auto-complete food suggestions.
 * Implementations can fetch from USDA API, local database, or cache.
 */
public interface SuggestionProvider {
    List<String> fetchSuggestions(String query) throws Exception;
}
