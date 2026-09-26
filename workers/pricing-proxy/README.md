# pricing-proxy (Cloudflare Worker)

Key-holding proxy for product prices. The Android app talks to this instead of
calling UPCitemdb directly once the free keyless tier is outgrown (or a paid
key is added — a key shipped in the APK would be extracted and abused).

## Deploy

Needs your own Cloudflare account (free tier is plenty: 100k req/day).

```bash
cd workers/pricing-proxy
npx wrangler login
npx wrangler deploy
```

Note the printed `*.workers.dev` URL, then in
`app/src/main/java/com/barelabel/app/pricing/PriceFetcher.java`:

```java
private static final boolean USE_WORKER = true;
private static final String WORKER_URL = "https://barelabel-pricing.<your-account>.workers.dev/price";
```

## Going paid

The trial endpoint allows 100 lookups/day with no key. When that's not
enough, buy a UPCitemdb plan, point `UPCITEMDB_URL` at
`https://api.upcitemdb.com/prod/v1/lookup`, and store the key as a secret
(never in the repo):

```bash
npx wrangler secret put UPCITEMDB_KEY
```

Edge caching (12h per GTIN) keeps paid usage near zero either way.
