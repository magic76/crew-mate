package com.crewpocket.mate.config;

import android.content.Context;
import android.content.SharedPreferences;

public final class AppConfig {
    public static final String PROVIDER_IN_PERSON = "in_person";
    public static final String PROVIDER_FAKE = "fake";
    public static final String PROVIDER_TELEGRAM = "telegram";

    private static final String PREFS = "crew_mate";
    private static final String KEY_GEMINI_API_KEY = "gemini_api_key";
    private static final String KEY_VOICE = "voice";
    private static final String KEY_MESSAGING_PROVIDER = "messaging_provider";
    private static final String KEY_TELEGRAM_BOT_TOKEN = "telegram_bot_token";

    private AppConfig() {}

    public static String getApiKey(Context context) {
        return prefs(context).getString(KEY_GEMINI_API_KEY, "");
    }

    public static void setApiKey(Context context, String value) {
        prefs(context).edit().putString(KEY_GEMINI_API_KEY, clean(value)).apply();
    }

    public static String getVoice(Context context) {
        return prefs(context).getString(KEY_VOICE, "Kore");
    }

    public static void setVoice(Context context, String value) {
        prefs(context).edit().putString(KEY_VOICE, clean(value).isEmpty() ? "Kore" : clean(value)).apply();
    }

    public static String getMessagingProvider(Context context) {
        String value = prefs(context).getString(KEY_MESSAGING_PROVIDER, PROVIDER_IN_PERSON);
        if (PROVIDER_TELEGRAM.equals(value)) return PROVIDER_TELEGRAM;
        if (PROVIDER_FAKE.equals(value)) return PROVIDER_FAKE;
        return PROVIDER_IN_PERSON;
    }

    public static void setMessagingProvider(Context context, String value) {
        String normalized = PROVIDER_IN_PERSON;
        if (PROVIDER_TELEGRAM.equals(value)) normalized = PROVIDER_TELEGRAM;
        else if (PROVIDER_FAKE.equals(value)) normalized = PROVIDER_FAKE;
        prefs(context).edit().putString(KEY_MESSAGING_PROVIDER, normalized).apply();
    }

    public static String getTelegramBotToken(Context context) {
        return prefs(context).getString(KEY_TELEGRAM_BOT_TOKEN, "");
    }

    public static void setTelegramBotToken(Context context, String value) {
        prefs(context).edit().putString(KEY_TELEGRAM_BOT_TOKEN, clean(value)).apply();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
