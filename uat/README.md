# BareLabel UAT driver (debug builds only)

Lets an external tester drive the app and read back UI state without touching
the screen. The driver lives in `app/src/debug`, so it is compiled into debug
APKs only and **can never ship in a release build**. No `src/main` code was
changed to add it.

## How it works

1. Install the **debug** APK (`barelabel-debug.apk`). The driver auto-starts
   via `UatInitProvider` (declared in the debug manifest).
2. A background thread polls this file:
   `uat/commands.json` on the `uat-driver-debug` branch
   (raw.githubusercontent.com, `no-cache`).
3. When `enabled` is `true`, each command with an unseen `id` is executed in
   order and the result is POSTed as JSON to `resultUrl` (use a
   [webhook.site](https://webhook.site) URL — no auth needed on either side).
   Per-command `resultUrl` overrides the feed-level one.
4. Standby poll is 30s; active poll is `pollMs` (min 500ms).

`search`/`scan` are asynchronous in the app: they return `{accepted:true}`
immediately. The script pattern is `search` → `wait` → `getState`.

## Feed format

```json
{
  "enabled": true,
  "pollMs": 2000,
  "resultUrl": "https://webhook.site/xxxx",
  "commands": [
    { "id": "c1", "action": "ping" },
    { "id": "c2", "action": "search", "args": { "query": "bread" } },
    { "id": "c3", "action": "wait",   "args": { "ms": 8000 } },
    { "id": "c4", "action": "getState" },
    { "id": "c5", "action": "screenshot", "args": { "maxWidth": 720 } },
    { "id": "c6", "action": "buyQuery" }
  ]
}
```

## Actions

| action | args | result |
|---|---|---|
| `ping` | — | `{debug, version, activity}` — verifies the driver is alive |
| `search` | `{query}` | sets the search box and invokes the real search pipeline; `{accepted:true}` |
| `scan` | `{gtin}` | injects a GTIN into the barcode path, bypassing the camera; `{accepted:true}` |
| `wait` | `{ms}` | sleeps on the driver thread (max 120s); `{waitedMs}` |
| `getState` | — | `{state}`: status/verdict text + visibility, result card, progress spinner, affiliate disclosure, primary product (name, brand, brandName, brandOwner, gtinUpc, foodCategory, flagged list), exact/more row counts + scraped row text |
| `screenshot` | `{maxWidth}` (default 720) | `{image: {width, height, base64Jpeg}}` |
| `buyQuery` | — | `{buy: {query, searchUrl}}` — the shopping query the app would use, without opening a browser tab |

Errors come back as `{ok:false, error:"..."}` with the command `id` and
`action` attached.

## Example session

See `uat/example_session.json` — a full bread-search pass: ping, search,
wait, state, screenshot, buy query. Copy its `commands` array into
`uat/commands.json`, set `enabled:true` and your webhook.site `resultUrl`,
push, and watch results arrive.

## Test plan

`uat/UAT_PLAN.md` — 55 cases, each with a clear goal, exact driver commands,
and observable pass criteria. Start there, not with ad-hoc feeds.

## Updating the feed

The app polls the **committed** file on this branch, so updating commands is a
commit + push to `uat-driver-debug` (same PAT ritual as any push). Keep
commands small; each result POST is one webhook.site request.

## Security notes

- Debug-only by construction (`app/src/debug`). **Never merge this branch into
  `main`** and never cherry-pick these files into a release-bound branch.
- The driver executes its commands via reflection against app internals. In a
  debug build on a test device this is fine; in a shipped app it would be a
  remote-control vulnerability.
- `resultUrl` exfiltrates screenshots — use a private webhook.site URL and
  don't point it at a shared one.
