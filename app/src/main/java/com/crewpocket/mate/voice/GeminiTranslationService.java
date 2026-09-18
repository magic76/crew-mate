package com.crewpocket.mate.voice;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Lightweight display-only translation helper.
 *
 * This is deliberately outside AgentHarness. Its output updates Message UI metadata only and is
 * never submitted back to the agent/model conversation as user or external speech.
 */
public final class GeminiTranslationService {
    private static final String MODEL = "gemini-2.5-flash-lite";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    public interface Listener {
        void onTranslated(String translatedText);
        void onError();
    }

    private final String apiKey;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    public GeminiTranslationService(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public void translate(String text, String sourceLanguage, String targetLanguage, final Listener listener) {
        String value = text == null ? "" : text.trim();
        String target = targetLanguage == null ? "" : targetLanguage.trim();
        if (value.isEmpty() || target.isEmpty() || apiKey.isEmpty()) {
            if (listener != null) listener.onError();
            return;
        }

        try {
            String source = sourceLanguage == null || sourceLanguage.trim().isEmpty()
                    ? "AUTO"
                    : sourceLanguage.trim();
            String instruction = "Translate this spoken conversation text from " + source
                    + " into " + target
                    + ". Preserve names, numbers, prices, dates and intent exactly. "
                    + "Return only the natural translation with no quotes, labels, markdown or explanation.\n\n"
                    + value;

            JSONObject body = new JSONObject()
                    .put("contents", new JSONArray().put(new JSONObject()
                            .put("role", "user")
                            .put("parts", new JSONArray().put(new JSONObject().put("text", instruction)))))
                    .put("generationConfig", new JSONObject()
                            .put("temperature", 0)
                            .put("maxOutputTokens", 512));

            Request request = new Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/"
                            + MODEL + ":generateContent?key=" + apiKey)
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException e) {
                    if (listener != null) listener.onError();
                }

                @Override public void onResponse(Call call, Response response) throws IOException {
                    try {
                        if (!response.isSuccessful() || response.body() == null) {
                            if (listener != null) listener.onError();
                            return;
                        }
                        JSONObject root = new JSONObject(response.body().string());
                        JSONArray candidates = root.optJSONArray("candidates");
                        if (candidates == null || candidates.length() == 0) {
                            if (listener != null) listener.onError();
                            return;
                        }
                        JSONObject content = candidates.optJSONObject(0).optJSONObject("content");
                        JSONArray parts = content == null ? null : content.optJSONArray("parts");
                        StringBuilder translated = new StringBuilder();
                        if (parts != null) {
                            for (int i = 0; i < parts.length(); i++) {
                                JSONObject part = parts.optJSONObject(i);
                                if (part == null) continue;
                                String piece = part.optString("text", "").trim();
                                if (piece.isEmpty()) continue;
                                if (translated.length() > 0) translated.append("\n");
                                translated.append(piece);
                            }
                        }
                        String result = translated.toString().trim();
                        if (result.isEmpty()) {
                            if (listener != null) listener.onError();
                        } else if (listener != null) {
                            listener.onTranslated(result);
                        }
                    } catch (Exception ignored) {
                        if (listener != null) listener.onError();
                    } finally {
                        response.close();
                    }
                }
            });
        } catch (Exception ignored) {
            if (listener != null) listener.onError();
        }
    }
}
