package com.barelabel.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

public class CategoryPreferenceManager {

    private static SharedPreferences getPrefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    public static boolean isCategoryEnabled(Context context, String category) {
        // Defaults to true if the preference hasn't been set yet
        return getPrefs(context).getBoolean(category, true);
    }

    public static void setCategoryEnabled(Context context, String category, boolean enabled) {
        getPrefs(context).edit().putBoolean(category, enabled).apply();
    }
}
