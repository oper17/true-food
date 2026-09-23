package com.example.barelabel;

import com.example.barelabel.model.ProductResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public class AlternateRanker {

    private static final Pattern ORGANIC_PATTERN =
            Pattern.compile("\\borganic\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern NON_GMO_PATTERN =
            Pattern.compile("\\bnon[\\s-]*gmo\\b", Pattern.CASE_INSENSITIVE);

    /** True when at least one ingredient is labeled organic (e.g. "ORGANIC CANE SUGAR"). */
    public static boolean hasOrganicIngredient(ProductResult item) {
        return item != null && item.ingredients != null
                && ORGANIC_PATTERN.matcher(item.ingredients).find();
    }

    /** True when the product is labeled non-GMO (name or ingredients). */
    public static boolean isNonGmo(ProductResult item) {
        if (item == null) return false;
        String text = (item.name == null ? "" : item.name) + " "
                + (item.ingredients == null ? "" : item.ingredients);
        return NON_GMO_PATTERN.matcher(text).find();
    }

    public static class RankedProduct {
        public final ProductResult product;
        public final int rank;
        public final int ingredientCount;
        public final int superiorCount;
        public final boolean hasOrganic;
        public final boolean nonGmo;
        private final boolean preferOrganic;

        public RankedProduct(ProductResult product, int rank, int ingredientCount,
                             int superiorCount, boolean hasOrganic, boolean nonGmo,
                             boolean preferOrganic) {
            this.product = product;
            this.rank = rank;
            this.ingredientCount = ingredientCount;
            this.superiorCount = superiorCount;
            this.hasOrganic = hasOrganic;
            this.nonGmo = nonGmo;
            this.preferOrganic = preferOrganic;
        }

        // 3 Stars for superior ingredients normally; in prefer-organic mode the
        // stars track the organic tiers instead (organic > non-GMO > rest).
        public String getStarRating() {
            if (preferOrganic) {
                if (hasOrganic) {
                    return "★★★";
                } else if (nonGmo) {
                    return "★★☆";
                } else {
                    return "★☆☆";
                }
            }
            if (superiorCount > 0) {
                return "★★★";
            } else if (rank <= 2) {
                return "★★☆";
            } else {
                return "★☆☆";
            }
        }

        /** Short tag explaining the organic tier, shown when prefer-organic is on. */
        public String getOrganicTag() {
            if (hasOrganic) return " • Organic";
            if (nonGmo) return " • Non-GMO";
            return "";
        }
    }

    /**
     * Filters out flagged products (Rank = Infinity) and ranks clean products
     * using ingredient count and superior ingredient bonuses.
     */
    public static List<RankedProduct> rankAndFilter(
            List<ProductResult> rawAlternates,
            Set<String> superiorTerms) {
        return rankAndFilter(rawAlternates, superiorTerms, false);
    }

    /**
     * Same as above, but with prefer-organic on the ranking becomes:
     * items with an organic ingredient first (3 stars), then non-GMO items,
     * then the rest — with superior count and ingredient count as tiebreakers.
     */
    public static List<RankedProduct> rankAndFilter(
            List<ProductResult> rawAlternates,
            Set<String> superiorTerms,
            boolean preferOrganic) {

        List<ProductResult> cleanOnly = new ArrayList<>();

        // 1. Filter out flagged items
        for (ProductResult item : rawAlternates) {
            if (item.flagged == null || item.flagged.isEmpty()) {
                cleanOnly.add(item);
            }
        }

        if (cleanOnly.isEmpty()) {
            return new ArrayList<>();
        }

        // 2. Sort
        if (preferOrganic) {
            Collections.sort(cleanOnly, (a, b) -> {
                int orgA = hasOrganicIngredient(a) ? 0 : 1;
                int orgB = hasOrganicIngredient(b) ? 0 : 1;
                if (orgA != orgB) {
                    return Integer.compare(orgA, orgB); // organic first
                }
                int gmoA = isNonGmo(a) ? 0 : 1;
                int gmoB = isNonGmo(b) ? 0 : 1;
                if (gmoA != gmoB) {
                    return Integer.compare(gmoA, gmoB); // non-GMO next
                }
                int supA = countSuperiorTerms(a, superiorTerms);
                int supB = countSuperiorTerms(b, superiorTerms);
                if (supA != supB) {
                    return Integer.compare(supB, supA); // higher superior count first
                }
                return Integer.compare(countIngredients(a), countIngredients(b));
            });
        } else {
            Collections.sort(cleanOnly, (a, b) -> {
                int supA = countSuperiorTerms(a, superiorTerms);
                int supB = countSuperiorTerms(b, superiorTerms);
                if (supA != supB) {
                    return Integer.compare(supB, supA); // Higher superior count comes first
                }
                return Integer.compare(countIngredients(a), countIngredients(b)); // Fewer ingredients comes first
            });
        }

        // 3. Assign Ranks
        List<RankedProduct> rankedList = new ArrayList<>();
        int currentRank = 1;

        for (int i = 0; i < cleanOnly.size(); i++) {
            ProductResult item = cleanOnly.get(i);
            int ingCount = countIngredients(item);
            int supCount = countSuperiorTerms(item, superiorTerms);

            if (i > 0) {
                ProductResult prev = cleanOnly.get(i - 1);
                int prevSup = countSuperiorTerms(prev, superiorTerms);
                int prevIng = countIngredients(prev);

                if (supCount < prevSup || ingCount > prevIng) {
                    currentRank++;
                }
            }

            rankedList.add(new RankedProduct(item, currentRank, ingCount, supCount,
                    hasOrganicIngredient(item), isNonGmo(item), preferOrganic));
        }

        return rankedList;
    }

    public static int countIngredients(ProductResult item) {
        if (item.ingredients == null || item.ingredients.trim().isEmpty()) {
            return Integer.MAX_VALUE;
        }
        return item.ingredients.split(",").length;
    }

    public static int countSuperiorTerms(ProductResult item, Set<String> superiorTerms) {
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
