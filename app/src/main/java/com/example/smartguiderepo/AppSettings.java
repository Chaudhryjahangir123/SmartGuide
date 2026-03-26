package com.example.smartguiderepo;

import android.content.Context;
import android.content.SharedPreferences;

public class AppSettings {
    // Current application language state
    public static boolean isEnglish = true;

    private static final String PREF_NAME = "SmartGuidePrefs";
    private static final String KEY_FIRST_TIME = "is_first_time";

    /**
     * Checks if the onboarding has been played before.
     */
    public static boolean isFirstTime(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_FIRST_TIME, true);
    }

    /**
     * Marks the onboarding as completed so it doesn't play again automatically.
     */
    public static void setFirstTimeDone(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_FIRST_TIME, false).apply();
    }
}