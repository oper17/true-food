package com.example.barelabel;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;

public class UsdaResponseCache {

    private static final String TAG = "UsdaResponseCache";
    private static final long TTL_MILLIS = TimeUnit.DAYS.toMillis(7); // 7 days TTL

    public static String get(Context context, String urlOrQuery) {
        try {
            File cacheFile = getCacheFile(context, urlOrQuery);
            if (!cacheFile.exists()) {
                return null;
            }

            // Check TTL expiration
            long age = System.currentTimeMillis() - cacheFile.lastModified();
            if (age > TTL_MILLIS) {
                cacheFile.delete();
                return null;
            }

            // Read cached response
            StringBuilder sb = new StringBuilder();
            try (FileInputStream fis = new FileInputStream(cacheFile);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "Failed to read cache", e);
            return null;
        }
    }

    public static void put(Context context, String urlOrQuery, String jsonResponse) {
        try {
            File cacheFile = getCacheFile(context, urlOrQuery);
            try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
                fos.write(jsonResponse.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to write cache", e);
        }
    }

    private static File getCacheFile(Context context, String key) throws Exception {
        // Hash key (URL or query string) to avoid filesystem character issues
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(key.getBytes(StandardCharsets.UTF_8));
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }

        File cacheDir = new File(context.getCacheDir(), "usda_http_cache");
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        return new File(cacheDir, hexString.toString() + ".json");
    }
}
