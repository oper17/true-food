/**
 * BareLabel pricing proxy (Cloudflare Worker).
 *
 * GET /price?gtin=<raw gtin/upc>
 *   -> { "ok": true, "gtin": "<normalized>", "price": 11.38,
 *        "merchant": "Wal-Mart.com", "currency": "USD" }
 *   -> { "ok": true, "gtin": "<normalized>", "price": null, ... }
 *        when no offer is known.
 *
 * The worker normalizes the GTIN (digits only, left-pad to 12, repair the
 * GS1 check digit — USDA gtinUpc values are often malformed), forwards to
 * UPCitemdb, picks the cheapest in-stock offer, and caches the result at the
 * edge for 12h (Cache API). Prices for groceries don't move minute to minute,
 * so this keeps paid-API usage near zero.
 *
 * Free tier: swap UPCITEMDB_URL to the paid v1 endpoint and set the
 * UPCITEMDB_KEY secret:
 *   npx wrangler secret put UPCITEMDB_KEY
 * (paid requests send `user_key` / `key_type: 3DES` headers — see
 * https://www.upcitemdb.com/docs)
 */

const UPCITEMDB_URL = "https://api.upcitemdb.com/prod/trial/lookup";
const CACHE_TTL_SECONDS = 12 * 3600;

function normalizeGtin(raw) {
  let d = String(raw || "").replace(/\D/g, "");
  if (d.length < 11) return "";
  while (d.length < 12) d = "0" + d;
  if (d.length > 14) d = d.slice(-14);
  const payload = d.slice(0, -1);
  let sum = 0;
  let times3 = true;
  for (let i = payload.length - 1; i >= 0; i--) {
    const v = payload.charCodeAt(i) - 48;
    sum += times3 ? v * 3 : v;
    times3 = !times3;
  }
  const want = (10 - (sum % 10)) % 10;
  if (d.charCodeAt(d.length - 1) - 48 !== want) d = payload + want;
  return d;
}

function pickBestOffer(data) {
  const items = (data && data.items) || [];
  if (!items.length) return null;
  let best = null;
  for (const o of items[0].offers || []) {
    if (String(o.availability || "").toLowerCase().includes("out of stock")) continue;
    const price = parseFloat(o.price);
    if (!isFinite(price) || price <= 0) continue;
    if (!best || price < best.price) {
      best = { price, merchant: o.merchant || "", currency: o.currency || "USD" };
    }
  }
  return best;
}

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    if (url.pathname !== "/price") {
      return new Response("Not found", { status: 404 });
    }
    const gtin = normalizeGtin(url.searchParams.get("gtin"));
    if (!gtin) {
      return Response.json({ ok: false, error: "invalid gtin" }, { status: 400 });
    }

    const cache = caches.default;
    const cacheKey = new Request(url.toString(), request);
    const cached = await cache.match(cacheKey);
    if (cached) return cached;

    const headers = {};
    if (env.UPCITEMDB_KEY) {
      // Paid v1 endpoint: UPCITEMDB_URL should point at /prod/v1/lookup then.
      headers["user_key"] = env.UPCITEMDB_KEY;
      headers["key_type"] = "3DES";
    }
    const upstream = await fetch(`${UPCITEMDB_URL}?upc=${gtin}`, { headers });
    const data = await upstream.json().catch(() => null);
    const best = pickBestOffer(data);

    const body = {
      ok: true,
      gtin,
      price: best ? best.price : null,
      merchant: best ? best.merchant : "",
      currency: best ? best.currency : "USD",
    };
    const resp = Response.json(body);
    resp.headers.set("Cache-Control", `public, max-age=${CACHE_TTL_SECONDS}`);
    ctx.waitUntil(cache.put(cacheKey, resp.clone()));
    return resp;
  },
};
