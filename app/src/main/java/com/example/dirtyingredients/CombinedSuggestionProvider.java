package com.example.dirtyingredients;

import java.util.ArrayList;
import java.util.List;

/**
 * Merges offline unbranded completions with USDA branded suggestions.
 * Unbranded completions always take precedence: they occupy rank 1-2 when
 * available, and USDA suggestions fill the remaining slots.
 */
public class CombinedSuggestionProvider implements SuggestionProvider {

    private static final int MAX_UNBRANDED = 2;

    private final UnbrandedSuggestionProvider unbrandedProvider;
    private final SuggestionProvider usdaProvider;

    public CombinedSuggestionProvider(UnbrandedSuggestionProvider unbrandedProvider,
                                      SuggestionProvider usdaProvider) {
        this.unbrandedProvider = unbrandedProvider;
        this.usdaProvider = usdaProvider;
    }

    @Override
    public List<String> fetchSuggestions(String query) throws Exception {
        List<String> merged = new ArrayList<>();

        for (String s : unbrandedProvider.getCompletions(query, MAX_UNBRANDED)) {
            if (!merged.contains(s)) {
                merged.add(s);
            }
        }

        List<String> usdaSuggestions = usdaProvider.fetchSuggestions(query);
        if (usdaSuggestions != null) {
            for (String s : usdaSuggestions) {
                if (!merged.contains(s)) {
                    merged.add(s);
                }
            }
        }

        return merged;
    }
}
