package com.example.barelabel.model;

import com.example.barelabel.FlaggedIngredientManager;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** One USDA branded product with its analyzed ingredient flags. */
public class ProductResult {
    public boolean found;
    public String name, brand, ingredients;
    public String foodCategory = "";
    public String gtinUpc = "";
    public String brandName = "";
    public String brandOwner = "";
    public MatchQuality matchQuality;
    public FlaggedIngredientManager.MatchResult matchResult;
    public Set<String> flagged = new HashSet<>();

    public ProductResult() {
    }

    public ProductResult(boolean found, String name, String brand, String ingredients,
                         FlaggedIngredientManager.MatchResult matchResult, MatchQuality matchQuality) {
        this.found = found;
        this.name = name;
        this.brand = brand;
        this.ingredients = ingredients;
        this.matchResult = matchResult;
        this.matchQuality = matchQuality;

        // Reset and strictly populate only active matches
        this.flagged.clear();
        if (matchResult != null && matchResult.hasMatches()) {
            for (List<String> items : matchResult.categoryMap.values()) {
                this.flagged.addAll(items);
            }
        }
    }

    public ProductResult(boolean found, String name, String brand, String ingredients,
                         FlaggedIngredientManager.MatchResult matchResult) {
        this(found, name, brand, ingredients, matchResult, MatchQuality.NONE);
    }

    public static ProductResult notFound() {
        return new ProductResult(false, "", "", "", new FlaggedIngredientManager.MatchResult(), MatchQuality.NONE);
    }
}
