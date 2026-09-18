package com.crewpocket.mate.config;

import android.content.Context;
import android.content.SharedPreferences;

import com.crewpocket.mate.model.AudioOutputMode;
import com.crewpocket.mate.model.InterfaceLanguage;

public final class AppConfig {
    private static final String PREFS = "crew_mate";
    private static final String KEY_GEMINI_API_KEY = "gemini_api_key";
    private static final String KEY_VOICE = "voice";
    private static final String KEY_AUDIO_OUTPUT_MODE = "audio_output_mode";
    private static final String KEY_INTERFACE_LANGUAGE = "interface_language";

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

    public static AudioOutputMode getAudioOutputMode(Context context) {
        String value = prefs(context).getString(KEY_AUDIO_OUTPUT_MODE, AudioOutputMode.MEDIA.name());
        try {
            return AudioOutputMode.valueOf(value);
        } catch (Exception ignored) {
            return AudioOutputMode.MEDIA;
        }
    }

    public static void setAudioOutputMode(Context context, AudioOutputMode mode) {
        AudioOutputMode safe = mode == null ? AudioOutputMode.MEDIA : mode;
        prefs(context).edit().putString(KEY_AUDIO_OUTPUT_MODE, safe.name()).apply();
    }

    public static InterfaceLanguage getInterfaceLanguage(Context context) {
        String value = prefs(context).getString(KEY_INTERFACE_LANGUAGE, InterfaceLanguage.ZH.name());
        try {
            return InterfaceLanguage.valueOf(value);
        } catch (Exception ignored) {
            return InterfaceLanguage.ZH;
        }
    }

    public static void setInterfaceLanguage(Context context, InterfaceLanguage language) {
        InterfaceLanguage safe = language == null ? InterfaceLanguage.ZH : language;
        prefs(context).edit().putString(KEY_INTERFACE_LANGUAGE, safe.name()).apply();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
