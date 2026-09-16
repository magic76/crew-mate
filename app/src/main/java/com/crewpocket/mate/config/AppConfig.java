package com.crewpocket.mate.config;

import android.content.Context;
import android.content.SharedPreferences;

public final class AppConfig {
    private static final String PREFS = "crew_mate";
    private static final String KEY_GEMINI_API_KEY = "gemini_api_key";
    private static final String KEY_VOICE = "voice";

    private AppConfig() {}

    public static String getApiKey(Context context) {
        return prefs(context).getString(KEY_GEMINI_API_KEY, "");
    }

    public static void setApiKey(Context context, String value) {
        prefs(context).edit().putString(KEY_GEMINI_API_KEY, value == null ? "" : value.trim()).apply();
    }

    public static String getVoice(Context context) {
        return prefs(context).getString(KEY_VOICE, "Kore");
    }

    public static void setVoice(Context context, String value) {
        prefs(context).edit().putString(KEY_VOICE, value == null || value.trim().isEmpty() ? "Kore" : value.trim()).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
