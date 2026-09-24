package com.barelabel.app.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Result of an alternates search: the clean candidates plus the flagged
 * categories seen across scanned (dirty) candidates, so the UI can explain
 * why a category search came up empty.
 * <p>
 * {@code singleFilterBlockCounts} maps each filter category to the number of
 * scanned candidates blocked ONLY by that category. Unchecking such a filter
 * brings exactly that many items back, which powers the smart "relax a
 * filter" suggestions in the empty state.
 */
public class AlternateSearchResult {
    public final List<ProductResult> alternates = new ArrayList<>();
    public final Set<String> flaggedCategories = new LinkedHashSet<>();
    public final Map<String, Integer> singleFilterBlockCounts = new LinkedHashMap<>();
}
