# TrueFood — Android app

Screen packaged-food ingredients against *your* flagged-ingredient list, and get
ranked clean alternatives. Product data comes from
[USDA FoodData Central](https://fdc.nal.usda.gov/).

## What it does

1. **Branded product check** — Type a product name (or scan its barcode) and the
   app looks it up in USDA FoodData Central (`dataType=Branded`), pulls the
   ingredient list, and flags matches against the categories in
   `app/src/main/assets/flagged_ingredients.json`. Verdicts: **CLEAN**,
   **DIRTY** (flagged ingredients grouped by category), **PRODUCT NOT FOUND**,
   or **INGREDIENTS UNAVAILABLE**. A missing product is never treated as clean.
2. **Your flagged list** — Toggle whole flagged-ingredient categories on/off in
   the "Filter Flagged Categories" panel; choices persist across launches. Only
   enabled categories are checked.
3. **Clean alternates** — When a product is dirty, the app finds its USDA
   `foodCategory` (rule-based classifier as fallback), searches that category,
   drops every flagged item, and ranks the clean ones (superior-ingredient
   count, then fewest ingredients). Up to 5 are shown with star ratings; tapping
   one shows its ingredients and a Walmart search link.
4. **Unbranded category search, no toggle** — If your query doesn't name the
   matched product's brand (e.g. "peanut butter" vs "jif peanut butter"), the
   app treats it as a category search and always shows clean choices for that
   category, even when the top hit is clean.
5. **Barcode scanning** — On-device ML Kit barcode scanning (CameraX). The
   scanned GTIN is matched exactly against USDA's `gtinUpc` field (trying
   as-scanned and leading-zero-stripped spellings) before falling back to a
   keyword search.

## Setup

The app needs a free USDA FoodData Central API key
([get one here](https://api.nal.usda.gov)). Add it to a `local.properties`
file in the project root (this file is git-ignored):

```properties
USDA_API_KEY=your_key_here
```

Gradle injects it into `BuildConfig.USDA_API_KEY` at build time.

## Build

```bash
./gradlew assembleDebug
```

In AndroidIDE, open/import the project, let Gradle sync, and build the `app`
module.

## Data files

- `app/src/main/assets/flagged_ingredients.json` — flagged-ingredient
  categories. Each category has `default_enabled` and an `ingredients` list.
  Matching is word-boundary based to avoid substring false positives.
- `app/src/main/assets/superior_ingredients.txt` — "superior" sourcing/quality
  terms (organic, grass-fed, …) used for ranking and ★★★ badges.

## Notes

- USDA search responses are cached on-device with a 7-day TTL
  (`UsdaResponseCache`).
- Informational tool — always verify the package label before consumption.
