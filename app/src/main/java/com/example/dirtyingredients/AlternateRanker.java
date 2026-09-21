package com.example.dirtyingredients;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public class AlternateRanker {

    public static class RankedProduct {
        public final MainActivity.ProductResult product;
        public final int rank;
        public final int ingredientCount;
        public final int superiorCount;

        public RankedProduct(MainActivity.ProductResult product, int rank, int ingredientCount, int superiorCount) {
            this.product = product;
            this.rank = rank;
            this.ingredientCount = ingredientCount;
            this.superiorCount = superiorCount;
        }

        // 3 Stars ONLY if it contains superior ingredients
        public String getStarRating() {
            if (superiorCount > 0) {
                return "★★★";
            } else if (rank <= 2) {
                return "★★☆";
            } else {
                return "★☆☆";
            }
        }
    }

    /**
     * Filters out flagged products (Rank = Infinity) and ranks clean products 
     * using ingredient count and superior ingredient bonuses.
     */
    public static List<RankedProduct> rankAndFilter(
            List<MainActivity.ProductResult> rawAlternates, 
            Set<String> superiorTerms) {

        List<MainActivity.ProductResult> cleanOnly = new ArrayList<>();

        // 1. Filter out flagged items
        for (MainActivity.ProductResult item : rawAlternates) {
            if (item.flagged == null || item.flagged.isEmpty()) {
                cleanOnly.add(item);
            }
        }

        if (cleanOnly.isEmpty()) {
            return new ArrayList<>();
        }

        // 2. Sort: Superior count descending, then total ingredient count ascending
        Collections.sort(cleanOnly, (a, b) -> {
            int supA = countSuperiorTerms(a, superiorTerms);
            int supB = countSuperiorTerms(b, superiorTerms);
            if (supA != supB) {
                return Integer.compare(supB, supA); // Higher superior count comes first
            }
            return Integer.compare(countIngredients(a), countIngredients(b)); // Fewer ingredients comes first
        });

        // 3. Assign Ranks
        List<RankedProduct> rankedList = new ArrayList<>();
        int currentRank = 1;

        for (int i = 0; i < cleanOnly.size(); i++) {
            MainActivity.ProductResult item = cleanOnly.get(i);
            int ingCount = countIngredients(item);
            int supCount = countSuperiorTerms(item, superiorTerms);

            if (i > 0) {
                MainActivity.ProductResult prev = cleanOnly.get(i - 1);
                int prevSup = countSuperiorTerms(prev, superiorTerms);
                int prevIng = countIngredients(prev);

                if (supCount < prevSup || ingCount > prevIng) {
                    currentRank++;
                }
            }

            rankedList.add(new RankedProduct(item, currentRank, ingCount, supCount));
        }

        return rankedList;
    }

    public static int countIngredients(MainActivity.ProductResult item) {
        if (item.ingredients == null || item.ingredients.trim().isEmpty()) {
            return Integer.MAX_VALUE;
        }
        return item.ingredients.split(",").length;
    }

    public static int countSuperiorTerms(MainActivity.ProductResult item, Set<String> superiorTerms) {
        if (superiorTerms == null || superiorTerms.isEmpty()) return 0;
        String text = (item.name + " " + item.ingredients).toLowerCase();
        int count = 0;
        for (String term : superiorTerms) {
            if (text.contains(term.toLowerCase())) {
                count++;
            }
        }
        return count;
    }
}
