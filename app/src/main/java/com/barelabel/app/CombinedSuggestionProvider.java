package com.barelabel.app;

import com.barelabel.app.model.Suggestion;

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
    public List<Suggestion> fetchSuggestions(String query) throws Exception {
        List<Suggestion> merged = new ArrayList<>();

        for (String s : unbrandedProvider.getCompletions(query, MAX_UNBRANDED)) {
            if (!containsLabel(merged, s)) {
                merged.add(new Suggestion(s, 0));
            }
        }

        List<Suggestion> usdaSuggestions = usdaProvider.fetchSuggestions(query);
        if (usdaSuggestions != null) {
            for (Suggestion s : usdaSuggestions) {
                if (!containsLabel(merged, s.label)) {
                    merged.add(s);
                }
            }
        }

        return merged;
    }

    private static boolean containsLabel(List<Suggestion> list, String label) {
        for (Suggestion s : list) {
            if (s.label.equals(label)) return true;
        }
        return false;
    }

    @Override
    public boolean isUnbrandedSuggestion(String suggestion) {
        // Stateless dictionary check — no cross-thread "last results" cache.
        return unbrandedProvider.isKnownTerm(suggestion);
    }
}
