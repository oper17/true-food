# Dirty Ingredients — AndroidIDE project

## What changed
This version no longer requires Google API credentials. It uses Open Food Facts for product lookup and ingredient data.

The app:
1. Loads `app/src/main/assets/flagged_ingredients.txt` into a HashSet at startup.
2. Searches Open Food Facts asynchronously for the entered product name.
3. Selects a matching result that has ingredient data when possible.
4. Checks the actual returned ingredient list against the HashSet.
5. Shows CLEAN, DIRTY, PRODUCT NOT FOUND, or INGREDIENTS UNAVAILABLE.

## Build in AndroidIDE
1. Open/import this project.
2. Let Gradle sync.
3. Build the `app` module.
4. Install the generated debug APK.

No Google API key is required.

## Flagged ingredients
Edit:
`app/src/main/assets/flagged_ingredients.txt`

One normalized term per line.

## Important data-quality behavior
A missing product is NOT treated as clean. The app explicitly says that it cannot determine the verdict when no verified ingredient list is available.

## Current lookup
The app uses Open Food Facts keyword search for name-based lookup. Open Food Facts documents API v3 as its current API, while noting that full-text search is not currently available in v2/v3 and the legacy v1 search endpoint supports keyword search. For a production version, a barcode scanner using the current product endpoint is preferable because barcode lookup is deterministic.

Open Food Facts also requests a descriptive User-Agent for API calls, which this app supplies.

## AndroidIDE Gradle launcher
This project includes `gradlew`. In AndroidIDE Terminal, from the project root, run:
`./gradlew assembleDebug`

The launcher delegates to AndroidIDE's installed Gradle and automatically supplies AndroidIDE's aapt2 override when `$HOME/.androidide/aapt2` exists.
