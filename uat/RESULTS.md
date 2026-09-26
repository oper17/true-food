# UAT Results — debug driver (running scoreboard)

Evidence codes: **D0** = ping session 2026-09-26 03:30 UTC · **D1** = batch 1
(search bread) 2026-09-26 03:36 UTC · **S** = static analysis vs shipped code/config.

## §1 Driver sanity
| Case | Verdict | Evidence / note |
|---|---|---|
| UAT-001 | PASS | D0: `{"ok":true,"debug":true,"version":"1.0","id":"t1"}` |
| UAT-002 | PASS | D0: getState ok on launch |

## §2 Search core
| Case | Verdict | Evidence / note |
|---|---|---|
| UAT-010 | PASS | D1: 55 exact rows for "bread" |
| UAT-011 | NOT-RUN | |
| UAT-012 | NOT-RUN | |
| UAT-013 | NOT-RUN | |
| UAT-014 | NOT-RUN | code has generation guards; needs device run |
| UAT-015 | NOT-RUN | |
| UAT-016 | NOT-RUN | |
| UAT-017 | NOT-RUN | |

## §3 Spam filter
| Case | Verdict | Evidence / note |
|---|---|---|
| UAT-020 | PASS | D1: no publisher/software/entertainment owners in bread rows |
| UAT-021 | NOT-RUN | |
| UAT-022 | NOT-RUN | |
| UAT-023 | PASS | D1: no pickles/chips in exact panel |
| UAT-024 | NOT-RUN | |
| UAT-025 | NOT-RUN | |

## §4 Label-brand display
| Case | Verdict | Evidence / note |
|---|---|---|
| UAT-030 | NOT-RUN | mechanism verified D1 (GRATEFUL shown, owner hidden) — Simply Balanced product itself untested |
| UAT-031 | NOT-RUN | batch 2 (scan) |
| UAT-032 | NOT-RUN | |
| UAT-033 | NOT-RUN | batch 2 (buyQuery after scan) |

## §5 Barcode scan
| Case | Verdict | Evidence / note |
|---|---|---|
| UAT-040 | NOT-RUN | batch 2 |
| UAT-041 | NOT-RUN | |
| UAT-042 | NOT-RUN | |
| UAT-043 | NOT-RUN | batch 2 |
| UAT-044 | NOT-RUN | batch 2 |

## §6 Ingredient analysis & verdict
| Case | Verdict | Evidence / note |
|---|---|---|
| UAT-050 | NOT-RUN | |
| UAT-051 | PASS | D1: primary flaggedCount 0, clean |
| UAT-052 | NOT-RUN | |
| UAT-053 | NOT-RUN | |
| UAT-054 | NOT-RUN | |
| UAT-055 | PASS | D1: "✓ Clean • 35 ingredients • 6 clean highlights" — sane |
| UAT-056 | FAIL | S: "yeast extract" not in config (matcher replicated 2026-09-26) |
| UAT-057 | FAIL | S: "hydrolyzed soy protein" not in config |
| UAT-058 | FAIL | S: celery powder / juice powder / cultured celery powder not in config |
| UAT-059 | FAIL | S: "modified corn starch", "modified tapioca starch" not in config |
| UAT-060 | FAIL | S: "maltodextrin" not in config |
| UAT-061 | FAIL | S: "dextrose", "fructose" not in config |
| UAT-062 | FAIL | S: "Red No. 40" label variant not matched ("red 40" only) |
| UAT-063 | FAIL | S: "caramel coloring" not matched ("caramel color" only) |
| UAT-064 | PASS | S: all control terms verified hitting (corn syrup solids, autolyzed yeast extract, nitrite, red 40, …) |

## §7 Pricing
| Case | Verdict | Evidence / note |
|---|---|---|
| UAT-065 | PASS | D1: "$3.98 · Wal-Mart.com", "$3.79 · Target" on rows 2–3, correct format + placement |
| UAT-066 | NOT-RUN | D1 consistent (rows 2–3 priced) but not a full check |
| UAT-067 | NOT-RUN | |
| UAT-068 | NOT-RUN | |

## §8 Affiliate, disclosure & buy
| Case | Verdict | Evidence / note |
|---|---|---|
| UAT-070 | NOT-RUN | batch 2 |
| UAT-071 | PASS | D1: disclosure visible on rows |
| UAT-072 | NOT-RUN | batch 2 |
| UAT-073 | NOT-RUN | batch 2 |

## §9 UI states & rendering
| Case | Verdict | Evidence / note |
|---|---|---|
| UAT-080 | NOT-RUN | |
| UAT-081 | NOT-RUN | batch 2 (screenshot) |
| UAT-082 | NOT-RUN | |
| UAT-083 | NOT-RUN | |

## §10 Robustness
| Case | Verdict | Evidence / note |
|---|---|---|
| UAT-090 | NOT-RUN | |
| UAT-091 | NOT-RUN | |
| UAT-092 | NOT-RUN | |
| UAT-093 | NOT-RUN | |
| UAT-094 | NOT-RUN | |

## §11 Regression sweep
| Case | Verdict | Evidence / note |
|---|---|---|
| UAT-100 | NOT-RUN | |
| UAT-101 | NOT-RUN | |
| UAT-102 | NOT-RUN | |
| UAT-103 | NOT-RUN | |

## §12 Teardown
| Case | Verdict | Evidence / note |
|---|---|---|
| UAT-110 | NOT-RUN | run last |

## §13 Manual-only
| Case | Verdict | Evidence / note |
|---|---|---|
| UAT-120 – UAT-128 | MANUAL | camera, Custom Tab, history, compare, save, retailer pref, airplane mode, jank, install — 9 cases, human on device |

## Totals

| | Count |
|---|---|
| PASS | 10 |
| FAIL | 8 (all §6 synonym gaps — config, not code) |
| NOT-RUN | 45 |
| MANUAL | 9 |
| **Total** | **72** |

## Fix list for failed cases (UAT-056 – UAT-063)

All eight are fixed by extending `app/src/main/assets/flagged_ingredients.json`
— no code change needed (matcher already handles the rest). Assets are packaged,
so: edit JSON → rebuild debug APK → reinstall → re-run UAT-056–064.

Add:
- **Flavor Enhancers & Additives**: `yeast extract`, `hydrolyzed soy protein`
  (consider also `hydrolyzed corn protein`, `torula yeast`)
- **Preservatives**: `celery powder`, `celery juice powder`, `cultured celery powder`
- **Emulsifiers & Thickening Agents**: `modified corn starch`, `modified tapioca starch`
- **Processed Sweeteners & Fibers**: `maltodextrin`, `dextrose`, `fructose`
- **Artificial Colors**: `red no. 40`, `yellow no. 5`, `yellow no. 6`,
  `blue no. 1`, `blue no. 2`, `caramel coloring`

Deliberately NOT added: plain `sugar` / `cane sugar` (clean products contain
it; flagging it would flip ✓ Clean breads to dirty — product decision).
