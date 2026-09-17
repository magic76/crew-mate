package com.crewpocket.mate.voice;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Base64;

import com.crewpocket.mate.model.SpeechAudience;
import com.magic76.crew.agent.ModelEvent;
import com.magic76.crew.agent.ModelSession;
import com.magic76.crew.agent.SessionConfig;
import com.magic76.crew.agent.ToolCall;
import com.magic76.crew.agent.ToolResult;
import com.magic76.crew.agent.ToolSpec;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/** Gemini Live provider adapter for the provider-neutral shared ModelSession contract. */
public final class GeminiLiveModelSession implements ModelSession {
    private static final String HOST = "generativelanguage.googleapis.com";
    private static final String WS_PATH = "/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent";
    private static final int INPUT_RATE = 16000;
    private static final int OUTPUT_RATE = 24000;

    public interface UiListener {
        void onStatus(String status);
        void onInputTranscript(String text, SpeechAudience audience);
        void onSpeakingChanged(boolean speaking);
        void onError(String message);
    }

    private final Context context;
    private final String apiKey;
    private final String voiceName;
    private final UiListener uiListener;
    private final OkHttpClient httpClient;
    private final ExecutorService audioWriter = Executors.newSingleThreadExecutor();
    private final Map<String, String> callNames = new ConcurrentHashMap<String, String>();

    private ModelSession.Listener modelListener;
    private SessionConfig config;
    private WebSocket webSocket;
    private volatile boolean running;
    private volatile boolean setupReady;
    private volatile boolean recording;
    private volatile boolean speaking;
    private volatile SpeechAudience speechAudience = SpeechAudience.MATE_HANDLING;
    private volatile boolean userAudioEnabled;
    private volatile boolean playbackEnabled;
    private volatile String channelMode = "REMOTE";
    private AudioRecord recorder;
    private AudioTrack player;
    private Thread micThread;

    public GeminiLiveModelSession(Context context, String apiKey, String voiceName, UiListener uiListener) {
        this.context = context.getApplicationContext();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.voiceName = normalizeVoice(voiceName);
        this.uiListener = uiListener;
        this.httpClient = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(10, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public synchronized void start(SessionConfig config, ModelSession.Listener listener) {
        if (running) return;
        if (config == null) throw new IllegalArgumentException("session config is null");
        if (listener == null) throw new IllegalArgumentException("model listener is null");
        if (apiKey.isEmpty()) throw new IllegalStateException("Gemini API key is empty");
        this.config = config;
        this.modelListener = listener;
        running = true;
        setupReady = false;
        status("Connecting to Gemini Live…");

        Request request = new Request.Builder().url("wss://" + HOST + WS_PATH + "?key=" + apiKey).build();
        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket socket, Response response) {
                try {
                    socket.send(buildSetup().toString());
                } catch (Exception e) {
                    fail("Unable to initialize Gemini Live: " + e.getMessage(), e);
                }
            }

            @Override public void onMessage(WebSocket socket, String text) { handleServerMessage(text); }
            @Override public void onMessage(WebSocket socket, ByteString bytes) { handleServerMessage(bytes.utf8()); }

            @Override public void onClosed(WebSocket socket, int code, String reason) {
                if (running) fail("Gemini Live disconnected: " + code + " " + reason, null);
            }

            @Override public void onFailure(WebSocket socket, Throwable t, Response response) {
                fail("Gemini Live connection failed: " + (t == null ? "Unknown network error" : t.getMessage()), t);
            }
        });
    }

    @Override
    public void sendUserText(String text) {
        if (!running || !setupReady || webSocket == null || text == null || text.trim().isEmpty()) return;
        try {
            webSocket.send(new JSONObject()
                    .put("realtimeInput", new JSONObject().put("text", text.trim()))
                    .toString());
        } catch (Exception e) {
            fail("Unable to send text context: " + e.getMessage(), e);
        }
    }

    @Override
    public void sendUserAudio(byte[] audioBytes) {
        if (!userAudioEnabled) return;
        if (!running || !setupReady || webSocket == null || audioBytes == null || audioBytes.length == 0) return;
        try {
            JSONObject audio = new JSONObject()
                    .put("mimeType", "audio/pcm;rate=16000")
                    .put("data", Base64.encodeToString(audioBytes, Base64.NO_WRAP));
            webSocket.send(new JSONObject()
                    .put("realtimeInput", new JSONObject().put("audio", audio))
                    .toString());
        } catch (Exception e) {
            fail("Microphone stream error: " + e.getMessage(), e);
        }
    }

    @Override
    public void sendToolResult(ToolResult result) {
        if (!running || webSocket == null || result == null) return;
        try {
            String name = callNames.remove(result.callId());
            JSONObject resultPayload = new JSONObject();
            resultPayload.put("ok", result.success());
            if (result.success()) {
                for (Map.Entry<String, Object> entry : result.payload().entrySet()) {
                    resultPayload.put(entry.getKey(), JSONObject.wrap(entry.getValue()));
                }
            } else {
                resultPayload.put("error_code", result.errorCode());
                resultPayload.put("error", result.errorMessage());
            }

            JSONObject item = new JSONObject()
                    .put("response", new JSONObject().put("result", resultPayload))
                    .put("id", result.callId());
            if (name != null && !name.isEmpty()) item.put("name", name);

            webSocket.send(new JSONObject().put("toolResponse",
                    new JSONObject().put("functionResponses", new JSONArray().put(item))).toString());
        } catch (Exception e) {
            fail("Unable to send tool result: " + e.getMessage(), e);
        }
    }

    @Override
    public void interrupt() {
        flushPlayback();
        if (userAudioEnabled) {
            try { sendAudioStreamEnd(); } catch (Exception ignored) {}
        }
    }

    @Override
    public synchronized void close() {
        if (running && setupReady && userAudioEnabled) sendAudioStreamEnd();
        running = false;
        setupReady = false;
        stopAudio();
        if (webSocket != null) {
            try { webSocket.close(1000, "Session closed"); } catch (Exception ignored) {}
            webSocket = null;
        }
        callNames.clear();
        status("Stopped");
    }

    public boolean isRunning() { return running; }
    public boolean isUserAudioEnabled() { return userAudioEnabled; }
    public boolean isPlaybackEnabled() { return playbackEnabled; }
    public SpeechAudience speechAudience() { return speechAudience; }

    /**
     * Authoritative physical speech boundary. Every audience transition ends the previous microphone
     * stream, releases AudioRecord, flushes playback, sends explicit audience/channel context, then
     * starts a fresh microphone stream only for PRIVATE_TO_MATE or EXTERNAL_WITH_MATE.
     */
    public synchronized void setSpeechAudience(SpeechAudience audience) {
        SpeechAudience next = audience == null ? SpeechAudience.MATE_HANDLING : audience;
        if (speechAudience == next) {
            userAudioEnabled = next.routesMicrophoneToMate();
            playbackEnabled = next.playsMateVoice();
            if (running && setupReady) {
                if (playbackEnabled) ensurePlayer(); else releasePlayer();
                if (userAudioEnabled && !recording) startInputAudio();
            }
            return;
        }

        boolean previousMic = userAudioEnabled;
        if (previousMic && running && setupReady) sendAudioStreamEnd();
        if (previousMic || recording || recorder != null) stopInputAudio();
        flushPlayback();
        releasePlayer();
        setSpeaking(false);

        speechAudience = next;
        userAudioEnabled = next.routesMicrophoneToMate();
        playbackEnabled = next.playsMateVoice();

        if (running && setupReady) {
            sendClientContextNow();
            if (playbackEnabled) ensurePlayer();
            if (userAudioEnabled) startInputAudio();
            status(userAudioEnabled ? "Listening" : "Ready");
        }
    }

    public synchronized void setChannelMode(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase();
        channelMode = "IN_PERSON".equals(normalized) ? "IN_PERSON" : "REMOTE";
        if (running && setupReady) sendClientContextNow();
    }

    private String buildAudienceContext() {
        String prefix = "AUDIENCE_MODE=" + speechAudience.name() + "\nCHANNEL_MODE=" + channelMode + "\n";
        switch (speechAudience) {
            case PRIVATE_TO_MATE:
                return prefix
                        + "Next microphone speech is the USER speaking privately to Mate. "
                        + "Treat it as private instruction. Do not expose or copy the private brief to the other person.";
            case EXTERNAL_WITH_MATE:
                return prefix
                        + "Next microphone speech is the OTHER PERSON speaking directly with Mate in an in-person conversation. "
                        + "Do not treat it as a private user instruction. Respond directly to that person, keep the user's private goal and constraints active, "
                        + "and do not call remote send_message for spoken replies.";
            case USER_DIRECT:
                return prefix
                        + "The user personally took over the human conversation. Do not speak and do not send messages until control returns.";
            case MATE_HANDLING:
                return prefix
                        + "There is no microphone speaker. Continue delegated work only through normal product events/tools when appropriate.";
            case IDLE:
            default:
                return prefix + "No live speaker is assigned.";
        }
    }

    private void sendClientContextNow() {
        if (!running || !setupReady || webSocket == null) return;
        try {
            JSONObject turn = new JSONObject()
                    .put("role", "user")
                    .put("parts", new JSONArray().put(new JSONObject().put("text", buildAudienceContext())));
            JSONObject client = new JSONObject()
                    .put("turns", new JSONArray().put(turn))
                    .put("turnComplete", false);
            webSocket.send(new JSONObject().put("clientContent", client).toString());
        } catch (Exception e) {
            fail("Unable to set live audience context: " + e.getMessage(), e);
        }
    }

    private void sendAudioStreamEnd() {
        if (!running || !setupReady || webSocket == null) return;
        try {
            webSocket.send(new JSONObject()
                    .put("realtimeInput", new JSONObject().put("audioStreamEnd", true))
                    .toString());
        } catch (Exception ignored) {}
    }

    private JSONObject buildSetup() throws Exception {
        JSONObject setup = new JSONObject();
        setup.put("model", "models/gemini-3.1-flash-live-preview");
        setup.put("generationConfig", new JSONObject()
                .put("responseModalities", new JSONArray().put("AUDIO"))
                .put("speechConfig", new JSONObject().put("voiceConfig",
                        new JSONObject().put("prebuiltVoiceConfig",
                                new JSONObject().put("voiceName", voiceName)))));
        setup.put("inputAudioTranscription", new JSONObject());
        setup.put("outputAudioTranscription", new JSONObject());
        setup.put("contextWindowCompression", new JSONObject().put("slidingWindow", new JSONObject()));
        setup.put("sessionResumption", new JSONObject());
        setup.put("systemInstruction", new JSONObject().put("parts",
                new JSONArray().put(new JSONObject().put("text", config.systemPrompt()))));
        setup.put("tools", new JSONArray().put(new JSONObject()
                .put("functionDeclarations", buildToolDeclarations(config))));
        return new JSONObject().put("setup", setup);
    }

    private JSONArray buildToolDeclarations(SessionConfig config) throws Exception {
        JSONArray declarations = new JSONArray();
        for (ToolSpec tool : config.tools()) {
            JSONObject schema = new JSONObject(tool.inputSchemaJson());
            normalizeSchemaTypes(schema);
            declarations.put(new JSONObject()
                    .put("name", tool.name())
                    .put("description", tool.description())
                    .put("parameters", schema));
        }
        return declarations;
    }

    private void normalizeSchemaTypes(Object value) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object child = object.opt(key);
                if ("type".equals(key) && child instanceof String) {
                    object.put(key, ((String) child).toUpperCase());
                } else {
                    normalizeSchemaTypes(child);
                }
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) normalizeSchemaTypes(array.opt(i));
        }
    }

    private void handleServerMessage(String text) {
        if (!running || text == null) return;
        try {
            JSONObject response = new JSONObject(text);
            JSONObject apiError = response.optJSONObject("error");
            if (apiError != null) {
                fail(apiError.optString("message", "Gemini Live error"), null);
                return;
            }

            if (response.has("setupComplete") || response.has("setup_complete")) {
                setupReady = true;
                sendClientContextNow();
                if (playbackEnabled) ensurePlayer();
                if (userAudioEnabled) startInputAudio();
                status(userAudioEnabled ? "Listening" : "Ready");
                return;
            }

            JSONObject server = response.optJSONObject("serverContent");
            if (server == null) server = response.optJSONObject("server_content");
            if (server != null) handleServerContent(server);

            JSONObject toolCall = response.optJSONObject("toolCall");
            if (toolCall == null) toolCall = response.optJSONObject("tool_call");
            if (toolCall != null) {
                JSONArray calls = toolCall.optJSONArray("functionCalls");
                if (calls == null) calls = toolCall.optJSONArray("function_calls");
                if (calls != null) {
                    for (int i = 0; i < calls.length(); i++) {
                        JSONObject item = calls.optJSONObject(i);
                        if (item == null) continue;
                        String id = item.optString("id", "call_" + i);
                        String name = item.optString("name", "");
                        JSONObject args = item.optJSONObject("args");
                        if (args == null) args = new JSONObject();
                        callNames.put(id, name);
                        emit(ModelEvent.toolCall(new ToolCall(id, name, jsonToMap(args))));
                    }
                }
            }
        } catch (Exception e) {
            fail("Unable to parse Gemini Live event: " + e.getMessage(), e);
        }
    }

    private void handleServerContent(JSONObject server) throws Exception {
        if (server.optBoolean("interrupted", false)) {
            flushPlayback();
            setSpeaking(false);
            emit(ModelEvent.interrupted());
        }

        JSONObject input = server.optJSONObject("inputTranscription");
        if (input == null) input = server.optJSONObject("input_transcription");
        if (input != null && speechAudience.routesMicrophoneToMate()) {
            String value = input.optString("text", "").trim();
            if (!value.isEmpty() && uiListener != null) uiListener.onInputTranscript(value, speechAudience);
        }

        JSONObject output = server.optJSONObject("outputTranscription");
        if (output == null) output = server.optJSONObject("output_transcription");
        if (output != null) {
            String value = output.optString("text", "").trim();
            if (!value.isEmpty()) emit(ModelEvent.text(value));
        }

        JSONObject turn = server.optJSONObject("modelTurn");
        if (turn == null) turn = server.optJSONObject("model_turn");
        if (turn != null) {
            JSONArray parts = turn.optJSONArray("parts");
            if (parts != null) {
                for (int i = 0; i < parts.length(); i++) {
                    JSONObject part = parts.optJSONObject(i);
                    if (part == null) continue;
                    JSONObject inline = part.optJSONObject("inlineData");
                    if (inline == null) inline = part.optJSONObject("inline_data");
                    if (inline != null && inline.optString("mimeType", "").startsWith("audio/pcm")) {
                        final byte[] pcm = Base64.decode(inline.optString("data", ""), Base64.DEFAULT);
                        if (pcm.length > 0) {
                            emit(ModelEvent.audio(pcm));
                            if (playbackEnabled) {
                                ensurePlayer();
                                setSpeaking(true);
                                audioWriter.execute(new Runnable() {
                                    @Override public void run() { writeAudio(pcm); }
                                });
                            }
                        }
                    }
                }
            }
        }

        if (server.optBoolean("turnComplete", server.optBoolean("turn_complete", false))) {
            setSpeaking(false);
            emit(ModelEvent.turnCompleted());
            status(userAudioEnabled ? "Listening" : "Ready");
        }
    }

    private synchronized void startInputAudio() {
        if (!running || !setupReady || !userAudioEnabled || recording) return;
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail("Microphone permission is required.", null);
            return;
        }

        int minInput = AudioRecord.getMinBufferSize(INPUT_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int inputBuffer = Math.max(minInput * 4, 8192);
        try {
            recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    INPUT_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, inputBuffer);
        } catch (Exception first) {
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    INPUT_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, inputBuffer);
        }

        recording = true;
        recorder.startRecording();
        micThread = new Thread(new Runnable() {
            @Override public void run() { captureLoop(); }
        }, "CrewMateMic");
        micThread.start();
    }

    private synchronized void ensurePlayer() {
        if (!running || !setupReady || !playbackEnabled || player != null) return;
        int minOutput = AudioTrack.getMinBufferSize(OUTPUT_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        player = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(OUTPUT_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(Math.max(minOutput * 4, 24000))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        player.play();
    }

    private void captureLoop() {
        byte[] buffer = new byte[3200];
        while (running && recording && userAudioEnabled) {
            AudioRecord target = recorder;
            if (target == null) break;
            int read;
            try {
                read = target.read(buffer, 0, buffer.length);
            } catch (Exception ignored) {
                break;
            }
            if (read <= 0 || !userAudioEnabled) continue;
            byte[] frame = new byte[read];
            System.arraycopy(buffer, 0, frame, 0, read);
            sendUserAudio(frame);
        }
    }

    private void writeAudio(byte[] pcm) {
        try {
            AudioTrack target = player;
            if (running && playbackEnabled && target != null) {
                target.write(pcm, 0, pcm.length, AudioTrack.WRITE_BLOCKING);
            }
        } catch (Exception ignored) {}
    }

    private synchronized void stopInputAudio() {
        recording = false;
        AudioRecord target = recorder;
        recorder = null;
        micThread = null;
        if (target != null) {
            try { target.stop(); } catch (Exception ignored) {}
            try { target.release(); } catch (Exception ignored) {}
        }
    }

    private synchronized void releasePlayer() {
        AudioTrack target = player;
        player = null;
        if (target != null) {
            try { target.pause(); target.flush(); target.stop(); } catch (Exception ignored) {}
            try { target.release(); } catch (Exception ignored) {}
        }
    }

    private synchronized void stopAudio() {
        stopInputAudio();
        releasePlayer();
        setSpeaking(false);
    }

    private synchronized void flushPlayback() {
        AudioTrack target = player;
        if (target == null) return;
        try { target.pause(); target.flush(); target.play(); } catch (Exception ignored) {}
    }

    private void setSpeaking(boolean value) {
        if (!playbackEnabled) value = false;
        if (speaking == value) return;
        speaking = value;
        if (uiListener != null) uiListener.onSpeakingChanged(value);
    }

    private void emit(ModelEvent event) {
        ModelSession.Listener target = modelListener;
        if (target != null && event != null) target.onModelEvent(event);
    }

    private void status(String value) {
        if (uiListener != null) uiListener.onStatus(value);
    }

    private void fail(String message, Throwable error) {
        running = false;
        stopAudio();
        String value = message == null ? "Unknown Gemini Live error" : message;
        if (uiListener != null) uiListener.onError(value);
        emit(ModelEvent.error(error == null ? new RuntimeException(value) : error));
    }

    private static Map<String, Object> jsonToMap(JSONObject object) throws Exception {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            result.put(key, unwrapJson(object.opt(key)));
        }
        return result;
    }

    private static Object unwrapJson(Object value) throws Exception {
        if (value instanceof JSONObject) return jsonToMap((JSONObject) value);
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            java.util.List<Object> out = new java.util.ArrayList<Object>();
            for (int i = 0; i < array.length(); i++) out.add(unwrapJson(array.opt(i)));
            return out;
        }
        return value == JSONObject.NULL ? null : value;
    }

    private static String normalizeVoice(String value) {
        if (value == null) return "Kore";
        String voice = value.trim();
        if ("Puck".equalsIgnoreCase(voice)) return "Puck";
        if ("Charon".equalsIgnoreCase(voice)) return "Charon";
        if ("Fenrir".equalsIgnoreCase(voice)) return "Fenrir";
        if ("Aoede".equalsIgnoreCase(voice)) return "Aoede";
        return "Kore";
    }
}
