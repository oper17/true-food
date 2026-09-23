package com.example.dirtyingredients.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Result of an alternates search: the clean candidates plus the flagged
 * categories seen across scanned (dirty) candidates, so the UI can explain
 * why a category search came up empty.
 */
public class AlternateSearchResult {
    public final List<ProductResult> alternates = new ArrayList<>();
    public final Set<String> flaggedCategories = new LinkedHashSet<>();
}
