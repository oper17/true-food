package com.example.barelabel;

import com.example.barelabel.model.Suggestion;

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
    private final java.util.Set<String> lastUnbranded = new java.util.HashSet<>();

    public CombinedSuggestionProvider(UnbrandedSuggestionProvider unbrandedProvider,
                                      SuggestionProvider usdaProvider) {
        this.unbrandedProvider = unbrandedProvider;
        this.usdaProvider = usdaProvider;
    }

    @Override
    public List<Suggestion> fetchSuggestions(String query) throws Exception {
        List<Suggestion> merged = new ArrayList<>();
        lastUnbranded.clear();

        for (String s : unbrandedProvider.getCompletions(query, MAX_UNBRANDED)) {
            if (!containsLabel(merged, s)) {
                merged.add(new Suggestion(s, 0));
                lastUnbranded.add(s);
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
        return suggestion != null && lastUnbranded.contains(suggestion);
    }
}
