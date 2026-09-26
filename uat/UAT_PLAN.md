# BareLabel UAT Plan — debug driver

72 cases (63 driver-driven + 9 manual-only). Each case states its goal in one
line, the exact driver commands to run, and what to observe in the result /
`getState` output. Run via `uat/commands.json` (see `uat/README.md`): push
the case's commands, keep the app in the foreground, collect results from the
webhook, evaluate against **Expect**.

Conventions: command `id`s below are suggestions (`<case>a/b/c…`); they must
be unique within a feed push. `getState` fields referenced: `status`,
`verdict`, `resultCard`, `progress`, `disclosure`, `primary.{name, brand,
brandName, brandOwner, gtinUpc, foodCategory, flaggedCount, flagged}`,
`exactRows.{count, rows}`, `moreRows.{count, rows}`. Row strings are the
concatenated visible text of each result row.

Driver limits (do not write cases against these): no tap/scroll gestures, no
real camera, no Custom Tab rendering verification, no network toggling. Those
live in §13 Manual-only.

---

## 1. Driver sanity

### UAT-001 — Driver is alive and honest about build type
- **Goal:** Confirm the remote-control channel works before trusting any later result.
- **Commands:** `ping` → `getState`
- **Expect:** `{"ok":true,"debug":true}`. If `debug` is false, stop: wrong build.

### UAT-002 — Fresh-launch idle state is clean
- **Goal:** Baseline: app starts with no stale results or stuck loading UI.
- **Commands:** `getState` (app freshly launched, no prior command)
- **Expect:** `status`/`progress` gone or empty, `exactRows.count` 0, no crash.

## 2. Search core

### UAT-010 — Basic text search renders results
- **Goal:** The primary happy path: query → USDA → rendered result list.
- **Commands:** `search "bread"` → `wait 8000` → `getState`
- **Expect:** `exactRows.count` > 0, `primary.found` true, `status` gone (not stuck on "Searching…").

### UAT-011 — Gibberish query degrades gracefully
- **Goal:** Nonsense input must not crash or hang the app.
- **Commands:** `search "xyzqwe123" id UAT-011a` → `wait 8000` → `getState`
- **Expect:** No crash; `exactRows.count` 0 or a no-results state; `status`/`progress` gone.

### UAT-012 — Multi-word branded query resolves
- **Goal:** Realistic query with brand intent returns relevant products.
- **Commands:** `search "peanut butter"` → `wait 8000` → `getState`
- **Expect:** `exactRows.count` > 0; row brand fields show label brands (see §5).

### UAT-013 — Query with surrounding whitespace
- **Goal:** Input hygiene: padded queries behave like trimmed ones.
- **Commands:** `search "  bread  "` → `wait 8000` → `getState`
- **Expect:** Same shape as UAT-010 (results render, count > 0).

### UAT-014 — Rapid successive searches show the latest query
- **Goal:** Regression for the stale-response race: an older async response must not overwrite newer results.
- **Commands:** `search "bread"` then immediately `search "peanut butter"` (no wait between) → `wait 10000` → `getState`
- **Expect:** Results are about peanut butter, not bread. No mixed/duplicated rows.

### UAT-015 — Single common-word query ("milk")
- **Goal:** Another single-token query through the spam-filter + category path.
- **Commands:** `search "milk"` → `wait 8000` → `getState`
- **Expect:** `exactRows.count` > 0; rows are dairy/milk products, no brand-spam owners.

### UAT-016 — Empty query does not crash
- **Goal:** Empty input is handled, not fatal.
- **Commands:** `search ""` → `wait 3000` → `getState`
- **Expect:** No crash; state unchanged or clean empty state.

### UAT-017 — Special characters in query
- **Goal:** Punctuation/unicode in queries doesn't break search or rendering.
- **Commands:** `search "bread & butter!"` → `wait 8000` → `getState`
- **Expect:** No crash; either results or clean no-results.

## 3. Spam filter (USDA brand-spam demotion)

### UAT-020 — "bread" suggestions contain no publisher/software spam
- **Goal:** The reported bug: USDA records filed under non-food companies (Multicom Publishing, Bang Brothers Entertainment, …) must not surface.
- **Commands:** `search "bread"` → `wait 8000` → `getState`
- **Expect:** No row mentions a publishing/software/entertainment/media brand owner; no bare generic descriptions from non-food companies.

### UAT-021 — "chips" returns real chips
- **Goal:** Spam filter must not over-correct on a query where junk and real products share words.
- **Commands:** `search "chips"` → `wait 8000` → `getState`
- **Expect:** Rows are actual chip products; `primary.foodCategory` is a snacks category.

### UAT-022 — "soda" returns real sodas
- **Goal:** Second probe of demotion precision on a spam-prone query.
- **Commands:** `search "soda"` → `wait 8000` → `getState`
- **Expect:** Real soda products; no non-food brand owners in exact rows.

### UAT-023 — Exact panel never shows "Bread & Butter Chips/Pickles" for "bread"
- **Goal:** Regression for the category-mismatch bug: single-token substring matching once put pickles under "Exact matches".
- **Commands:** `search "bread"` → `wait 8000` → `getState`
- **Expect:** Every exact row's product is bread-category; pickles/condiments appear only in "More" or not at all.

### UAT-024 — Exact panel category matches the panel's category
- **Goal:** Generalize UAT-023: exact matches must belong to the USDA foodCategory of the panel.
- **Commands:** `search "yogurt"` → `wait 8000` → `getState`
- **Expect:** Exact rows are yogurt-category products; obvious miscategorized items are demoted to More.

### UAT-025 — Small plausible brands are NOT treated as spam
- **Goal:** The filter must not nuke legit small brands (the rejected cluster-size heuristic's false-positive case).
- **Commands:** `search "peanut butter"` → `wait 8000` → `getState`
- **Expect:** Exact rows include multiple small but plausible peanut-butter brands.

## 4. Label-brand display (not brand owner)

### UAT-030 — "Simply Balanced" shows label brand, not contract manufacturer
- **Goal:** The reported bug: bubble showed "C.B. Dumont Co., Inc." instead of "Simply Balanced".
- **Commands:** `search "simply balanced bread"` → `wait 8000` → `getState`
- **Expect:** `primary.brandName` (and row brand text) is the label brand; `brandOwner` may still be the manufacturer but is not the displayed brand.

### UAT-031 — Giant Eagle product shows "GIANT EAGLE"
- **Goal:** USDA `brandName` preferred over `brandOwner` on rows and primary.
- **Commands:** `scan "03003409685"` → `wait 8000` → `getState`
- **Expect:** Displayed brand is GIANT EAGLE (USDA label brand), not the owner.

### UAT-032 — Alternates panel rows use label brands
- **Goal:** The bubble fix covered 5 call sites; alternates rows must not regress to owner-first.
- **Commands:** `search "bread"` → `wait 8000` → `getState`
- **Expect:** Spot-check first 6 exact rows: brand shown is the consumer-facing label brand.

### UAT-033 — OFF-sourced product keeps USDA label brand when OFF brands empty
- **Goal:** Regression for the brand-append gap: OFF entry with `product_name` but empty `brands` must backfill USDA's label brand.
- **Commands:** `scan "03003409685"` → `wait 8000` → `buyQuery`
- **Expect:** `buyQuery` query string contains GIANT EAGLE (the USDA label brand), not just the product name.

## 5. Barcode scan path

### UAT-040 — Scan resolves a real product end to end
- **Goal:** The scan path (camera bypassed) drives the real barcode pipeline: GTIN → product → verdict → alternates.
- **Commands:** `scan "03003409685"` → `wait 10000` → `getState`
- **Expect:** `primary.found` true; name/brand populated; `exactRows.count` > 0; no crash.

### UAT-041 — Scan of an invalid GTIN degrades gracefully
- **Goal:** Bad barcode data must not crash or hang.
- **Commands:** `scan "000000000000"` → `wait 8000` → `getState`
- **Expect:** No crash; product not found / empty state; loading UI dismissed.

### UAT-042 — Rescan of the same barcode is consistent
- **Goal:** Caching must not corrupt or duplicate results on repeat scans.
- **Commands:** `scan "03003409685"` → `wait 8000` → `getState` → `scan "03003409685"` → `wait 8000` → `getState`
- **Expect:** Both `getState` outputs describe the same product; no duplicated panels.

### UAT-043 — Scan populates alternates panels
- **Goal:** Scan path feeds the same alternates pipeline as search.
- **Commands:** `scan "03003409685"` → `wait 10000` → `getState`
- **Expect:** `exactRows` and/or `moreRows` non-empty with category-appropriate products.

### UAT-044 — Scan result shows verdict
- **Goal:** Ingredient analysis runs on the scanned product, not just search results.
- **Commands:** `scan "03003409685"` → `wait 10000` → `getState`
- **Expect:** `primary.flaggedCount` present (≥ 0); verdict view visible.

## 6. Ingredient analysis & verdict

### UAT-050 — Product with flagged ingredients gets a DIRTY verdict
- **Goal:** Core value prop: dirty product is called out with specifics.
- **Commands:** `search "peanut butter"` → `wait 8000` → `getState`
- **Expect:** At least one exact row (or primary) shows a non-clean verdict; `flaggedCount` > 0 with named ingredients.

### UAT-051 — Clean product gets a CLEAN verdict
- **Goal:** The other half of the verdict contract.
- **Commands:** `search "bread"` → `wait 8000` → `getState`
- **Expect:** Primary (or a clean row) shows clean verdict; `flaggedCount` 0.

### UAT-052 — Flagged ingredients name their categories
- **Goal:** Users need the *why*, not just a flag.
- **Commands:** `search "peanut butter"` → `wait 8000` → `getState`
- **Expect:** `flagged` entries carry category info (e.g. sweeteners, preservatives) in row text.

### UAT-053 — "natural flavor" is flagged
- **Goal:** Regression for the flagged-ingredient config fix.
- **Commands:** `search` a product known to list natural flavor (e.g. `"granola bar"`) → `wait 8000` → `getState`
- **Expect:** `flagged` includes natural flavor (or the row's verdict reflects it).

### UAT-054 — Product with no ingredient data degrades gracefully
- **Goal:** Missing data must not crash analysis or show a false verdict.
- **Commands:** `search "xyzqwe123"` → `wait 8000` → `getState` (or any product lacking ingredients)
- **Expect:** No crash; verdict area shows an unavailable/empty state, not a fabricated verdict.

### UAT-055 — Ingredient counts in rows are sane
- **Goal:** Row meta lines ("N ingredients • M clean highlights") are computed, not garbage.
- **Commands:** `search "bread"` → `wait 8000` → `getState`
- **Expect:** Row texts contain plausible counts (N > 0); highlights ≤ ingredients.

### Synonym & reformulation gaps (verified against config 2026-09-26)

Method: replicated `FlaggedIngredientManager.phrasePattern` (whole-word,
whitespace-tolerant, trailing-`s` plural) in Python and ran candidate synonyms
against the shipped `flagged_ingredients.json`. Controls all HIT: corn syrup
solids (via "corn syrup"), autolyzed yeast extract, sodium nitrite, modified
food starch, high fructose corn syrup, red 40, caramel color, natural flavor.
The cases below are the confirmed MISSES — each currently reads Clean and
should FAIL until the config is extended. Evaluate per row: find a row whose
ingredient text contains the synonym (human: read the full list on the phone
screen; `getState` row text may truncate with …). If that row shows ✓ Clean
(or no flagged entry for the category), the case fails as documented.

Deliberate non-flag (do NOT "fix"): plain "sugar"/"cane sugar" is intentionally
unflagged — ✓ Clean breads list organic cane sugar. The cases below target
synonyms of *flagged* concepts only.

### UAT-056 — Plain "yeast extract" flags as a flavor enhancer
- **Goal:** The most common glutamate reformulation; config lists only "autolyzed yeast extract".
- **Commands:** `search "veggie straws"` → `wait 8000` → `getState`
- **Expect:** Any row with "yeast extract" in ingredients shows a Flavor Enhancers & Additives flag, not ✓ Clean. KNOWN GAP (verified): term missing → currently Clean. FAIL until config extended.

### UAT-057 — "hydrolyzed soy protein" flags as a flavor enhancer
- **Goal:** Protein-source variant; config lists only "hydrolyzed vegetable protein".
- **Commands:** `search "ramen noodles"` → `wait 8000` → `getState`
- **Expect:** Rows with "hydrolyzed soy protein" flagged under Flavor Enhancers & Additives. KNOWN GAP (verified). FAIL until config extended.

### UAT-058 — "celery powder" / "celery juice powder" flags as a preservative
- **Goal:** The classic "no nitrates added*" reformulation — a natural nitrite source.
- **Commands:** `search "bacon"` → `wait 8000` → `getState`
- **Expect:** Rows with celery powder/juice powder flagged under Preservatives. KNOWN GAP (verified): all three variants ("celery powder", "celery juice powder", "cultured celery powder") miss. FAIL until config extended.

### UAT-059 — "modified corn starch" / "modified tapioca starch" flag
- **Goal:** Starch-source variants; config lists only food/potato/"modified starch".
- **Commands:** `search "canned soup"` → `wait 8000` → `getState`
- **Expect:** Rows with either variant flagged under Emulsifiers & Thickening Agents. KNOWN GAP (verified). FAIL until config extended.

### UAT-060 — "maltodextrin" flags as a processed sweetener/fiber
- **Goal:** Ultra-processed starch filler in the same family as flagged isomalto-oligosaccharide.
- **Commands:** `search "corn chips"` → `wait 8000` → `getState`
- **Expect:** Rows with "maltodextrin" flagged under Processed Sweeteners & Fibers. KNOWN GAP (verified). FAIL until config extended.

### UAT-061 — Standalone "dextrose" / "fructose" flag as processed sweeteners
- **Goal:** Category currently lists only HFCS/corn syrup/IMO; standalone refined sugars slip through.
- **Commands:** `search "gummy candy"` → `wait 8000` → `getState`
- **Expect:** Rows with "dextrose" or "fructose" flagged under Processed Sweeteners & Fibers. KNOWN GAP (verified). FAIL until config extended.

### UAT-062 — Label variant "Red No. 40" flags as artificial color
- **Goal:** Real labels print "Red No. 40"; config has only "red 40" and the matcher is literal.
- **Commands:** `search "candy"` → `wait 8000` → `getState`
- **Expect:** Rows with "Red No. 40" flagged under Artificial Colors. KNOWN GAP (verified). FAIL until config extended. (Same class: "Yellow No. 5/6", "Blue No. 1/2".)

### UAT-063 — Label variant "caramel coloring" flags as artificial color
- **Goal:** "Caramel coloring" vs listed "caramel color" — same ingredient, different label wording.
- **Commands:** `search "cola"` → `wait 8000` → `getState`
- **Expect:** Rows with "caramel coloring" flagged under Artificial Colors. KNOWN GAP (verified). FAIL until config extended.

### UAT-064 — Synonym controls still hit after config edits
- **Goal:** Guard against regressions when the gaps above are fixed: existing terms must keep matching.
- **Commands:** `search "bread"` → `wait 8000` → `getState` (spot-check any rows with corn syrup solids / caramel color / natural flavor)
- **Expect:** The verified controls (UAT-056–063 header) still flag exactly as before. Run after every `flagged_ingredients.json` edit.

## 7. Pricing prototype

### UAT-065 — First rows show live prices in the right format
- **Goal:** The pricing prototype renders cheapest-in-stock offers under exact rows.
- **Commands:** `search "bread"` → `wait 10000` → `getState`
- **Expect:** Rows that have prices match `$X.XX · Merchant` format; price appears under the meta line, not inside the ingredient text.

### UAT-066 — Only the first 3 exact rows are priced
- **Goal:** Prototype scope is exactly 3 rows; no more, no crash on the rest.
- **Commands:** `search "bread"` → `wait 10000` → `getState`
- **Expect:** At most the first 3 exact rows carry prices; rows 4+ show Buy without a price.

### UAT-067 — Missing price data is silent, not broken
- **Goal:** Uneven UPCitemdb coverage must not produce "null" text or broken rows.
- **Commands:** `search "bread"` → `wait 10000` → `getState`
- **Expect:** Rows without prices render normally; no "null", no "$0.00", no crash.

### UAT-068 — Prices survive a rescan/re-search
- **Goal:** Price cache path doesn't duplicate or misattribute prices.
- **Commands:** `search "bread"` → `wait 10000` → `search "bread"` → `wait 10000` → `getState`
- **Expect:** Prices still correctly attached to the same rows; no doubled price lines.

## 8. Affiliate, disclosure & buy flow

### UAT-070 — `buyQuery` returns a usable shopping URL without opening a tab
- **Goal:** Buy intent is captured as data for verification, no side effects during UAT.
- **Commands:** `search "bread"` → `wait 8000` → `buyQuery`
- **Expect:** `ok:true` with a `query`/`url` string; no app navigation occurred (verify via follow-up `getState`: same screen).

### UAT-071 — FTC disclosure is visible where buy links exist
- **Goal:** Compliance: commission disclosure shown alongside buy affordances.
- **Commands:** `search "bread"` → `wait 8000` → `getState`
- **Expect:** `disclosure.visibility` visible with the commission text on rows/cards that offer Buy.

### UAT-072 — Shopping query carries the label brand
- **Goal:** Buy queries must include the consumer-facing brand for relevance.
- **Commands:** `search "simply balanced bread"` → `wait 8000` → `buyQuery`
- **Expect:** Query string contains SIMPLY BALANCED (label brand), not the manufacturer name.

### UAT-073 — Shopping query carries the GTIN token
- **Goal:** GTIN as an extra token pins the exact variant in shopping results.
- **Commands:** `scan "03003409685"` → `wait 8000` → `buyQuery`
- **Expect:** Query string contains the GTIN digits alongside name/brand.

## 9. UI states & rendering

### UAT-080 — Loading state appears and clears
- **Goal:** Users see progress during the async search, and it goes away.
- **Commands:** `search "bread"` → `getState` (immediately, no wait) → `wait 8000` → `getState`
- **Expect:** First `getState`: `status` visible ("Searching…") and/or `progress` visible. Second: both gone, results present.

### UAT-081 — Results screenshot is visually sane
- **Goal:** Human eyeball check of the results layout (can't be asserted from text).
- **Commands:** `search "bread"` → `wait 8000` → `screenshot`
- **Expect:** Manual review: rows legible, brand bubbles, Buy/Save/Compare buttons, no overlapping text.

### UAT-082 — Verdict card screenshot is visually sane
- **Goal:** Human eyeball check of the verdict presentation.
- **Commands:** `scan "03003409685"` → `wait 10000` → `screenshot`
- **Expect:** Manual review: verdict readable, flagged ingredients listed, disclosure present.

### UAT-083 — Status text never sticks after completion
- **Goal:** No stale "Searching…" left visible once results render.
- **Commands:** `search "bread"` → `wait 10000` → `getState`
- **Expect:** `status.visibility` gone; if text remains it is not visible.

## 10. Robustness & edge cases

### UAT-090 — Search completes within a reasonable time
- **Goal:** Perf sanity on a real device/network.
- **Commands:** `search "bread"` → `wait 15000` → `getState`
- **Expect:** Results rendered well before the 15s wait ends (`status` gone at first check ideally).

### UAT-091 — Long session: 5 sequential searches, no degradation
- **Goal:** No leaks, no accumulating stale UI, no slowdown across a session.
- **Commands:** `search "bread"` → `wait 6000` → `search "milk"` → `wait 6000` → `search "peanut butter"` → `wait 6000` → `search "chips"` → `wait 6000` → `search "yogurt"` → `wait 8000` → `getState`
- **Expect:** Final state shows yogurt results; no crash; no rows from earlier queries mixed in.

### UAT-092 — Special GTIN spellings resolve the same product
- **Goal:** GTIN normalization (digit-strip, padding, check-digit repair) works for barcode variants.
- **Commands:** `scan "03003409685"` → `wait 8000` → `getState`
- **Expect:** Product found; `primary.gtinUpc` is the canonical form.

### UAT-093 — Background/foreground cycle doesn't kill the driver
- **Goal:** Driver survives the app being backgrounded and restored (as long as the process lives).
- **Commands:** `ping` → (manually background + foreground the app) → `wait 3000` → `ping`
- **Expect:** Both pings `ok:true`. (If the process was killed, the second ping never arrives — that itself is the finding.)

### UAT-094 — `getState` during active search doesn't crash
- **Goal:** State introspection is safe mid-flight.
- **Commands:** `search "bread"` → `getState` (immediately) → `wait 8000` → `getState`
- **Expect:** Both return `ok:true`; first may show loading state.

## 11. Regression sweep (post-fix verification)

### UAT-100 — Alternates pool skips brand-spam records
- **Goal:** The spam fix's second half: spam never enters the alternates candidate pool.
- **Commands:** `search "bread"` → `wait 10000` → `getState`
- **Expect:** No exact/more row carries a spam brand owner, even deep in the lists.

### UAT-101 — Recent searches still work after perf refactor
- **Goal:** The `RecentSearches` prefs migration didn't break history.
- **Commands:** `search "bread"` → `wait 6000` → `getState`
- **Expect:** No crash; search completes (history UI itself is manual — §13).

### UAT-102 — USDA retry path: results arrive despite a flaky first attempt
- **Goal:** Retry/backoff keeps the UX smooth on transient failures.
- **Commands:** `search "bread"` → `wait 12000` → `getState`
- **Expect:** Results present; cannot force the flaky path on demand, so a pass here is weak — note any `status` error text if the network actually hiccuped.

### UAT-103 — Throttled affiliate check doesn't block results
- **Goal:** Affiliate mapping refresh (parallel JSON/sig fetch) never stalls the results UI.
- **Commands:** `search "bread"` → `wait 8000` → `getState`
- **Expect:** Results render promptly; affiliate/disclosure state resolves independently.

## 12. Session teardown

### UAT-110 — Feed can be disarmed cleanly
- **Goal:** After testing, the driver goes quiet without uninstalling the app.
- **Commands:** (push feed with `"enabled": false`) → `wait 35000` → `ping`
- **Expect:** No result ever arrives for the post-disable ping; driver is dormant.

---

## 13. Manual-only (driver can't reach these)

- **UAT-120** — Real camera scan: point at a physical barcode; product resolves.
- **UAT-121** — Custom Tab: tap Buy; correct retailer URL opens with visible URL bar.
- **UAT-122** — Scan history page: scanned items persist across app restarts.
- **UAT-123** — Compare: pick 2 history items; side-by-side sheet is correct.
- **UAT-124** — Save: saved items persist; reappear after restart.
- **UAT-125** — Preferred retailer setting: Google/Amazon/Walmart fallback opens the right search.
- **UAT-126** — Airplane mode: search/scan show a graceful offline state, no crash.
- **UAT-127** — Animation/jank: scroll the alternates list; subjectively smooth.
- **UAT-128** — Install/update: clean install and upgrade-from-previous both launch clean.

---

## Running a batch

1. Put the case's commands into `uat/commands.json` (unique `id`s, `"enabled": true`).
2. Commit + push `uat-driver-debug` (needs a fresh PAT per push).
3. Open the debug app, keep it foregrounded and untouched.
4. Collect POSTs from the webhook; match `id`s to commands.
5. Evaluate each Expect; record pass/fail with the raw result bodies.

Keep batches small (3–6 commands): easier to attribute failures.
