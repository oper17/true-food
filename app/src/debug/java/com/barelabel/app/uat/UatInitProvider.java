package com.barelabel.app.uat;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import com.barelabel.app.BuildConfig;

/**
 * Debug-only entry point for the UAT driver.
 *
 * Lives in app/src/debug, so it is compiled into debug APKs only and can
 * never ship in a release build. Declared in the debug manifest; no changes
 * to src/main were needed to wire it up. A ContentProvider is used because it
 * initializes at app start without requiring a custom Application class.
 *
 * Belt-and-braces: does nothing unless BuildConfig.DEBUG is true.
 */
public class UatInitProvider extends ContentProvider {
    private static final String TAG = "UatInit";

    @Override
    public boolean onCreate() {
        Context ctx = getContext();
        if (ctx != null && BuildConfig.DEBUG) {
            try {
                UatDriver.start(ctx.getApplicationContext());
            } catch (Exception e) {
                Log.w(TAG, "UAT driver failed to start", e);
            }
        }
        return true;
    }

    // Unused; this provider exists only for its onCreate().
    @Override public Cursor query(Uri u, String[] p, String s, String[] sa, String so) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] sa) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] sa) { return 0; }
}
