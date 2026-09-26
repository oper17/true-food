package com.barelabel.app;

import com.barelabel.app.model.ProductResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.Locale;
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

    /** All ranking inputs for one product, computed exactly once (not per comparison). */
    private static final class Scored {
        final ProductResult item;
        final int ingredientCount;
        final int superiorCount;
        final boolean hasOrganic;
        final boolean nonGmo;

        Scored(ProductResult item, int ingredientCount, int superiorCount,
               boolean hasOrganic, boolean nonGmo) {
            this.item = item;
            this.ingredientCount = ingredientCount;
            this.superiorCount = superiorCount;
            this.hasOrganic = hasOrganic;
            this.nonGmo = nonGmo;
        }
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

        // Pre-lowercase the superior terms once — the old code lowercased
        // every term inside every countSuperiorTerms call (per comparison).
        List<String> lowerSuperior = new ArrayList<>();
        if (superiorTerms != null) {
            for (String term : superiorTerms) {
                if (term != null) lowerSuperior.add(term.toLowerCase(Locale.US));
            }
        }

        // 1. Filter out flagged items and precompute every score once.
        // The old code recomputed these inside the sort comparator
        // (O(n log n) regex + substring scans) and then again below.
        List<Scored> scored = new ArrayList<>();
        for (ProductResult item : rawAlternates) {
            if (item.flagged != null && !item.flagged.isEmpty()) {
                continue;
            }
            String lowerText = ((item.name == null ? "" : item.name) + " "
                    + (item.ingredients == null ? "" : item.ingredients)).toLowerCase(Locale.US);
            int supCount = 0;
            for (String term : lowerSuperior) {
                if (lowerText.contains(term)) supCount++;
            }
            // hasOrganicIngredient historically matches ingredients only —
            // keep that exact semantic.
            String ingredientsText = item.ingredients == null ? "" : item.ingredients;
            scored.add(new Scored(item, countIngredients(item), supCount,
                    ORGANIC_PATTERN.matcher(ingredientsText).find(),
                    NON_GMO_PATTERN.matcher(lowerText).find()));
        }

        if (scored.isEmpty()) {
            return new ArrayList<>();
        }

        // 2. Sort on the precomputed scores — the comparator does no
        // string work at all now.
        Comparator<Scored> bySuperiorThenCount = (a, b) -> {
            if (a.superiorCount != b.superiorCount) {
                return Integer.compare(b.superiorCount, a.superiorCount);
            }
            return Integer.compare(a.ingredientCount, b.ingredientCount);
        };
        if (preferOrganic) {
            Collections.sort(scored, (a, b) -> {
                int orgA = a.hasOrganic ? 0 : 1;
                int orgB = b.hasOrganic ? 0 : 1;
                if (orgA != orgB) {
                    return Integer.compare(orgA, orgB); // organic first
                }
                int gmoA = a.nonGmo ? 0 : 1;
                int gmoB = b.nonGmo ? 0 : 1;
                if (gmoA != gmoB) {
                    return Integer.compare(gmoA, gmoB); // non-GMO next
                }
                return bySuperiorThenCount.compare(a, b);
            });
        } else {
            Collections.sort(scored, bySuperiorThenCount);
        }

        // 3. Assign Ranks
        List<RankedProduct> rankedList = new ArrayList<>();
        int currentRank = 1;

        for (int i = 0; i < scored.size(); i++) {
            Scored s = scored.get(i);

            if (i > 0) {
                Scored prev = scored.get(i - 1);
                if (s.superiorCount < prev.superiorCount
                        || s.ingredientCount > prev.ingredientCount) {
                    currentRank++;
                }
            }

            rankedList.add(new RankedProduct(s.item, currentRank, s.ingredientCount,
                    s.superiorCount, s.hasOrganic, s.nonGmo, preferOrganic));
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
        String text = (item.name + " " + item.ingredients).toLowerCase(Locale.US);
        int count = 0;
        for (String term : superiorTerms) {
            if (text.contains(term.toLowerCase(Locale.US))) {
                count++;
            }
        }
        return count;
    }
}
