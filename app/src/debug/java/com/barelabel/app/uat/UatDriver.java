package com.barelabel.app.uat;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.barelabel.app.BuildConfig;
import com.barelabel.app.R;
import com.barelabel.app.ShoppingUrlBuilder;
import com.barelabel.app.images.ProductImageResolver;
import com.barelabel.app.model.ProductResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Debug-only UAT driver: lets an external tester (Pepper) drive the app and
 * read back UI state without touching the screen.
 *
 * <p>How it works: a background thread polls {@code uat/commands.json} on the
 * {@code uat-driver-debug} branch. When {@code enabled} is true, each unseen
 * command is executed (UI work marshaled to the main thread, app internals
 * reached via reflection so src/main stays untouched) and the result is POSTed
 * as JSON to {@code resultUrl} (e.g. a webhook.site URL).
 *
 * <p>Actions: ping, search {query}, scan {gtin}, wait {ms}, getState,
 * screenshot {maxWidth}, buyQuery. See uat/README.md for the protocol.
 *
 * <p>Debug-only by construction: this file lives in app/src/debug and is
 * never compiled into release builds.
 */
public final class UatDriver {
    private static final String TAG = "UatDriver";
    private static final String FEED_URL =
            "https://raw.githubusercontent.com/slplakshmipriya/true-food/uat-driver-debug/uat/commands.json";
    private static final String MAIN_ACTIVITY = "com.barelabel.app.MainActivity";

    private static volatile Activity foreground;
    private static volatile boolean started;

    private UatDriver() {}

    static void start(Context appContext) {
        if (started) return;
        started = true;
        if (appContext instanceof Application) {
            ((Application) appContext).registerActivityLifecycleCallbacks(
                    new Application.ActivityLifecycleCallbacks() {
                        @Override public void onActivityResumed(Activity a) { foreground = a; }
                        @Override public void onActivityPaused(Activity a) {
                            if (foreground == a) foreground = null;
                        }
                        @Override public void onActivityCreated(Activity a, Bundle b) {}
                        @Override public void onActivityStarted(Activity a) {}
                        @Override public void onActivityStopped(Activity a) {}
                        @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
                        @Override public void onActivityDestroyed(Activity a) {}
                    });
        }
        Thread t = new Thread(UatDriver::pollLoop, "uat-driver");
        t.setDaemon(true);
        t.start();
        Log.i(TAG, "UAT driver started (debug build only)");
    }

    // ---------------- poll loop ----------------

    private static void pollLoop() {
        Set<String> seen = new HashSet<>();
        while (true) {
            try {
                JSONObject root = new JSONObject(httpGet(FEED_URL, 10000));
                boolean enabled = root.optBoolean("enabled", false);
                long pollMs = Math.max(root.optLong("pollMs", 2000), 500);
                String resultUrl = root.optString("resultUrl", "");
                if (enabled) {
                    JSONArray cmds = root.optJSONArray("commands");
                    if (cmds != null) {
                        for (int i = 0; i < cmds.length(); i++) {
                            JSONObject c = cmds.getJSONObject(i);
                            String id = c.optString("id", "");
                            if (id.isEmpty() || seen.contains(id)) continue;
                            seen.add(id);
                            JSONObject res = execute(c);
                            res.put("id", id);
                            String url = c.optString("resultUrl", resultUrl);
                            if (!url.isEmpty()) {
                                try {
                                    httpPost(url, res.toString());
                                } catch (Exception e) {
                                    Log.w(TAG, "result POST failed for " + id, e);
                                }
                            } else {
                                Log.i(TAG, "result " + id + ": " + res);
                            }
                        }
                    }
                }
                Thread.sleep(enabled ? pollMs : 30000);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                Log.w(TAG, "poll failed", e);
                try {
                    Thread.sleep(15000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    // ---------------- command execution ----------------

    private static JSONObject execute(JSONObject c) {
        String action = c.optString("action", "");
        JSONObject args = c.optJSONObject("args");
        if (args == null) args = new JSONObject();
        JSONObject res = new JSONObject();
        try {
            res.put("action", action);
            switch (action) {
                case "ping":
                    res.put("ok", true);
                    res.put("debug", BuildConfig.DEBUG);
                    res.put("version", BuildConfig.VERSION_NAME);
                    res.put("activity", foreground == null
                            ? null : foreground.getClass().getSimpleName());
                    break;
                case "search": {
                    final String query = args.optString("query", "");
                    requireMain();
                    onUi(() -> {
                        EditText sb = (EditText) field(foreground, "searchBox");
                        sb.setText(query);
                        call(foreground, "search", new Class<?>[0], new Object[0]);
                        return null;
                    });
                    res.put("ok", true);
                    res.put("accepted", true);
                    res.put("query", query);
                    break;
                }
                case "scan": {
                    final String gtin = args.optString("gtin", "");
                    requireMain();
                    onUi(() -> {
                        call(foreground, "lookupBarcodeAndSearch",
                                new Class<?>[]{String.class}, new Object[]{gtin});
                        return null;
                    });
                    res.put("ok", true);
                    res.put("accepted", true);
                    res.put("gtin", gtin);
                    break;
                }
                case "wait": {
                    long ms = args.optLong("ms", 1000);
                    Thread.sleep(Math.min(ms, 120000));
                    res.put("ok", true);
                    res.put("waitedMs", ms);
                    break;
                }
                case "getState":
                    requireMain();
                    res.put("ok", true);
                    res.put("state", onUi(() -> dumpState(foreground)));
                    break;
                case "screenshot": {
                    requireMain();
                    final int maxWidth = args.optInt("maxWidth", 720);
                    res.put("ok", true);
                    res.put("image", onUi(() -> capture(foreground, maxWidth)));
                    break;
                }
                case "buyQuery": {
                    requireMain();
                    JSONObject q = onUi(() -> {
                        ProductResult p = (ProductResult) field(foreground,
                                "currentPrimaryResult");
                        String gtin = p == null ? "" : p.gtinUpc;
                        ProductImageResolver.OffProductInfo off =
                                ProductImageResolver.getCached(foreground, gtin);
                        String query = ShoppingUrlBuilder.buildQuery(p, off);
                        JSONObject o = new JSONObject();
                        o.put("query", query);
                        o.put("searchUrl", ShoppingUrlBuilder.buildSearchUrl(query));
                        return o;
                    });
                    res.put("ok", true);
                    res.put("buy", q);
                    break;
                }
                default:
                    res.put("ok", false);
                    res.put("error", "unknown action: " + action);
            }
        } catch (Exception e) {
            try {
                res.put("ok", false);
                res.put("error", e.toString());
            } catch (Exception ignored) {}
            Log.w(TAG, "command failed: " + action, e);
        }
        return res;
    }

    private static void requireMain() throws Exception {
        if (foreground == null) throw new IllegalStateException("no foreground activity");
        if (!MAIN_ACTIVITY.equals(foreground.getClass().getName())) {
            throw new IllegalStateException("foreground is "
                    + foreground.getClass().getSimpleName() + ", need MainActivity");
        }
    }

    // ---------------- state dump ----------------

    private static JSONObject dumpState(Activity act) throws Exception {
        JSONObject s = new JSONObject();
        s.put("status", viewState((TextView) field(act, "statusText")));
        s.put("verdict", viewState((TextView) field(act, "verdictText")));
        View card = act.findViewById(R.id.resultCard);
        s.put("resultCard", card == null ? "missing" : vis(card.getVisibility()));
        View progress = (View) field(act, "progress");
        s.put("progress", vis(progress.getVisibility()));
        View disclosure = act.findViewById(R.id.affiliateDisclosure);
        s.put("disclosure", disclosure == null ? "missing" : viewState((TextView) disclosure));

        ProductResult p = (ProductResult) field(act, "currentPrimaryResult");
        if (p != null) {
            JSONObject pr = new JSONObject();
            pr.put("found", p.found);
            pr.put("name", p.name);
            pr.put("brand", p.brand);
            pr.put("brandName", p.brandName);
            pr.put("brandOwner", p.brandOwner);
            pr.put("gtinUpc", p.gtinUpc);
            pr.put("foodCategory", p.foodCategory);
            pr.put("flaggedCount", p.flagged == null ? 0 : p.flagged.size());
            pr.put("flagged", p.flagged == null ? new JSONArray()
                    : new JSONArray(p.flagged).toString());
            s.put("primary", pr);
        } else {
            s.put("primary", JSONObject.NULL);
        }

        Object controller = field(act, "alternatesController");
        if (controller != null) {
            LinearLayout exact = (LinearLayout) field(controller, "exactList");
            LinearLayout more = (LinearLayout) field(controller, "moreList");
            s.put("exactRows", scrapeRows(exact));
            s.put("moreRows", scrapeRows(more));
        }
        return s;
    }

    /** Row count + scraped text of the first few rows of an alternates list. */
    private static JSONObject scrapeRows(LinearLayout list) throws Exception {
        JSONObject o = new JSONObject();
        if (list == null) {
            o.put("count", -1);
            return o;
        }
        int count = list.getChildCount();
        o.put("count", count);
        JSONArray rows = new JSONArray();
        // Rows and dividers interleave; scrape the first few TextView-bearing children.
        int scraped = 0;
        for (int i = 0; i < count && scraped < 6; i++) {
            View child = list.getChildAt(i);
            String text = scrapeText(child).trim();
            if (!text.isEmpty()) {
                rows.put(text.length() > 300 ? text.substring(0, 300) + "…" : text);
                scraped++;
            }
        }
        o.put("rows", rows);
        return o;
    }

    private static String scrapeText(View v) {
        StringBuilder sb = new StringBuilder();
        scrapeInto(v, sb);
        return sb.toString();
    }

    private static void scrapeInto(View v, StringBuilder sb) {
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && t.length() > 0) {
                if (sb.length() > 0) sb.append(" | ");
                sb.append(t.toString().replace('\n', ' '));
            }
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                scrapeInto(g.getChildAt(i), sb);
            }
        }
    }

    private static JSONObject viewState(TextView v) throws Exception {
        JSONObject o = new JSONObject();
        if (v == null) {
            o.put("visibility", "missing");
            return o;
        }
        o.put("visibility", vis(v.getVisibility()));
        CharSequence t = v.getText();
        o.put("text", t == null ? "" : t.toString());
        return o;
    }

    private static String vis(int visibility) {
        switch (visibility) {
            case View.VISIBLE: return "visible";
            case View.INVISIBLE: return "invisible";
            default: return "gone";
        }
    }

    // ---------------- screenshot ----------------

    private static JSONObject capture(Activity act, int maxWidth) throws Exception {
        View root = act.getWindow().getDecorView().getRootView();
        int w = root.getWidth();
        int h = root.getHeight();
        if (w <= 0 || h <= 0) throw new IllegalStateException("root view has no size");
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bmp));
        float scale = Math.min(1f, (float) maxWidth / w);
        Bitmap scaled = scale < 1f
                ? Bitmap.createScaledBitmap(bmp, Math.round(w * scale), Math.round(h * scale), true)
                : bmp;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, baos);
        if (scaled != bmp) scaled.recycle();
        bmp.recycle();
        JSONObject o = new JSONObject();
        o.put("width", scaled.getWidth());
        o.put("height", scaled.getHeight());
        o.put("base64Jpeg", Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP));
        return o;
    }

    // ---------------- threading / reflection / http ----------------

    private interface UiTask<T> {
        T run() throws Exception;
    }

    private static <T> T onUi(UiTask<T> task) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return task.run();
        AtomicReference<T> out = new AtomicReference<>();
        AtomicReference<Exception> err = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                out.set(task.run());
            } catch (Exception e) {
                err.set(e);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(20, TimeUnit.SECONDS)) throw new RuntimeException("UI task timed out");
        if (err.get() != null) throw err.get();
        return out.get();
    }

    private static Object field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static Object call(Object o, String name, Class<?>[] types, Object[] args)
            throws Exception {
        Method m = o.getClass().getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(o, args);
    }

    private static String httpGet(String url, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setRequestProperty("Cache-Control", "no-cache");
        try (InputStream in = c.getInputStream()) {
            return readAll(in);
        } finally {
            c.disconnect();
        }
    }

    private static void httpPost(String url, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream out = c.getOutputStream()) {
            out.write(bytes);
        }
        int code = c.getResponseCode();
        c.disconnect();
        if (code < 200 || code >= 300) throw new RuntimeException("POST -> HTTP " + code);
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) baos.write(buf, 0, n);
        return baos.toString("UTF-8");
    }
}
