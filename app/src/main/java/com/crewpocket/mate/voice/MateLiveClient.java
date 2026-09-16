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

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Gemini Live voice client adapted from Crew Teacher's NativeGeminiLiveClient.
 * Crew Mate keeps the realtime audio/session layer but replaces tutor behavior with
 * tool-driven communication-agent behavior.
 */
public class MateLiveClient {
    private static final String HOST = "generativelanguage.googleapis.com";
    private static final String WS_PATH = "/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent";
    private static final int INPUT_RATE = 16000;
    private static final int OUTPUT_RATE = 24000;

    public interface Listener {
        void onStatus(String status);
        void onTranscript(String text, String role);
        void onToolCall(String id, String name, JSONObject args);
        void onSpeakingChanged(boolean speaking);
        void onError(String message);
    }

    private final Context context;
    private final String apiKey;
    private final String voiceName;
    private final Listener listener;
    private final OkHttpClient httpClient;
    private final ExecutorService audioWriter = Executors.newSingleThreadExecutor();

    private WebSocket webSocket;
    private volatile boolean running;
    private volatile boolean setupReady;
    private volatile boolean recording;
    private Thread micThread;
    private AudioRecord recorder;
    private AudioTrack player;
    private volatile boolean speaking;

    public MateLiveClient(Context context, String apiKey, String voiceName, Listener listener) {
        this.context = context.getApplicationContext();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.voiceName = normalizeVoice(voiceName);
        this.listener = listener;
        this.httpClient = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(10, TimeUnit.SECONDS)
                .build();
    }

    public synchronized void start() {
        if (running) return;
        if (apiKey.isEmpty()) {
            listener.onError("Gemini API key is empty.");
            return;
        }
        running = true;
        setupReady = false;
        listener.onStatus("Connecting to Gemini Live…");

        Request request = new Request.Builder()
                .url("wss://" + HOST + WS_PATH + "?key=" + apiKey)
                .build();

        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket socket, Response response) {
                try {
                    socket.send(buildSetup().toString());
                } catch (Exception e) {
                    fail("Unable to initialize Gemini Live: " + e.getMessage());
                }
            }

            @Override public void onMessage(WebSocket socket, String text) {
                handleServerMessage(text);
            }

            @Override public void onMessage(WebSocket socket, ByteString bytes) {
                handleServerMessage(bytes.utf8());
            }

            @Override public void onClosed(WebSocket socket, int code, String reason) {
                if (running) fail("Gemini Live disconnected: " + code + " " + reason);
            }

            @Override public void onFailure(WebSocket socket, Throwable t, Response response) {
                String detail = t == null ? "Unknown network error" : t.getMessage();
                fail("Gemini Live connection failed: " + detail);
            }
        });
    }

    public synchronized void stop() {
        running = false;
        setupReady = false;
        stopAudio();
        if (webSocket != null) {
            try { webSocket.close(1000, "User stopped"); } catch (Exception ignored) {}
            webSocket = null;
        }
        listener.onStatus("Stopped");
    }

    public boolean isRunning() {
        return running;
    }

    /** Pushes hidden runtime context such as an external contact reply into the live session. */
    public boolean pushContext(String text) {
        if (!running || !setupReady || webSocket == null || text == null || text.trim().isEmpty()) return false;
        try {
            JSONObject root = new JSONObject();
            root.put("realtimeInput", new JSONObject().put("text", text.trim()));
            return webSocket.send(root.toString());
        } catch (Exception e) {
            return false;
        }
    }

    /** Matches the working tool-response envelope already used by Crew Teacher. */
    public boolean sendToolResponse(String id, String name, JSONObject result) {
        if (!running || webSocket == null) return false;
        try {
            JSONObject item = new JSONObject()
                    .put("response", new JSONObject().put("result",
                            result == null ? new JSONObject().put("ok", true) : result))
                    .put("id", id == null || id.isEmpty() ? "call_0" : id)
                    .put("name", name == null ? "" : name);
            JSONObject root = new JSONObject().put("toolResponse",
                    new JSONObject().put("functionResponses", new JSONArray().put(item)));
            return webSocket.send(root.toString());
        } catch (Exception e) {
            listener.onError("Unable to send tool result: " + e.getMessage());
            return false;
        }
    }

    private JSONObject buildSetup() throws Exception {
        JSONObject setup = new JSONObject();
        setup.put("model", "models/gemini-3.1-flash-live-preview");

        JSONObject generation = new JSONObject();
        generation.put("responseModalities", new JSONArray().put("AUDIO"));
        generation.put("speechConfig", new JSONObject().put("voiceConfig",
                new JSONObject().put("prebuiltVoiceConfig",
                        new JSONObject().put("voiceName", voiceName))));
        setup.put("generationConfig", generation);
        setup.put("inputAudioTranscription", new JSONObject());
        setup.put("outputAudioTranscription", new JSONObject());
        setup.put("contextWindowCompression", new JSONObject().put("slidingWindow", new JSONObject()));
        setup.put("sessionResumption", new JSONObject());
        setup.put("systemInstruction", new JSONObject().put("parts",
                new JSONArray().put(new JSONObject().put("text", systemPrompt()))));
        setup.put("tools", new JSONArray().put(new JSONObject()
                .put("functionDeclarations", toolDeclarations())));
        return new JSONObject().put("setup", setup);
    }

    private JSONArray toolDeclarations() throws Exception {
        JSONArray tools = new JSONArray();

        JSONObject createProps = new JSONObject();
        createProps.put("contact", schema("STRING", "Who Crew Mate should communicate with."));
        createProps.put("goal", schema("STRING", "The concrete outcome the user wants."));
        createProps.put("private_context", new JSONObject()
                .put("type", "ARRAY")
                .put("description", "Private preferences or constraints that should not be exposed verbatim unless needed.")
                .put("items", schema("STRING", "One private constraint.")));
        tools.put(declaration("create_task",
                "Create a communication task after the user asks Crew Mate to handle communication.",
                createProps, new JSONArray().put("contact").put("goal")));

        JSONObject contextProps = new JSONObject();
        contextProps.put("context", schema("STRING", "New private instruction, preference, or constraint from the user."));
        tools.put(declaration("update_task_context", "Add private context to the active task.",
                contextProps, new JSONArray().put("context")));

        JSONObject draftProps = new JSONObject();
        draftProps.put("text", schema("STRING", "The exact message Crew Mate proposes to send externally."));
        tools.put(declaration("draft_message", "Prepare an external message before sending it.",
                draftProps, new JSONArray().put("text")));

        JSONObject sendProps = new JSONObject();
        sendProps.put("text", schema("STRING", "The exact external message to send."));
        sendProps.put("user_approved", schema("BOOLEAN",
                "True only after the user explicitly approved a blocked high-risk message."));
        tools.put(declaration("send_message",
                "Send a message to the active task contact. The app Decision Gate may block it.",
                sendProps, new JSONArray().put("text")));

        JSONObject askProps = new JSONObject();
        askProps.put("question", schema("STRING", "The concise question Crew Mate needs the user to answer."));
        askProps.put("reason", schema("STRING", "Why the decision cannot safely be made from existing context."));
        tools.put(declaration("ask_user", "Pause the external task when a new user decision is required.",
                askProps, new JSONArray().put("question")));

        JSONObject completeProps = new JSONObject();
        completeProps.put("summary", schema("STRING", "Short outcome summary including commitments and next steps."));
        tools.put(declaration("complete_task", "Finish the task only after its goal is actually achieved.",
                completeProps, new JSONArray().put("summary")));
        return tools;
    }

    private JSONObject declaration(String name, String description, JSONObject properties, JSONArray required) throws Exception {
        JSONObject params = new JSONObject()
                .put("type", "OBJECT")
                .put("properties", properties);
        if (required != null && required.length() > 0) params.put("required", required);
        return new JSONObject()
                .put("name", name)
                .put("description", description)
                .put("parameters", params);
    }

    private JSONObject schema(String type, String description) throws Exception {
        return new JSONObject().put("type", type).put("description", description);
    }

    private String systemPrompt() {
        return "You are Crew Mate, the user's private AI communication assistant. "
                + "The user speaks to you privately by voice. Your job is to turn their intent into a communication task, "
                + "communicate with the other person through tools, keep the user informed, and finish the task.\n\n"
                + "RULES:\n"
                + "1. Speak naturally and concisely in the user's language.\n"
                + "2. Private user instructions are private. Never expose hidden reasoning or private context unnecessarily.\n"
                + "3. When the user asks you to contact or coordinate with someone, call create_task before any external action.\n"
                + "4. Use draft_message and send_message for external communication. Never pretend a message was sent unless the tool succeeds.\n"
                + "5. Never invent what the other person said. External replies arrive only as EXTERNAL_MESSAGE events.\n"
                + "6. Continue the task proactively after EXTERNAL_MESSAGE events.\n"
                + "7. If the reply introduces a decision not covered by the user's goal/preferences, call ask_user and ask the user briefly by voice.\n"
                + "8. Money, payment, sensitive data, cancellation, contracts, and important commitments require explicit user approval when the Decision Gate blocks them.\n"
                + "9. After explicit approval of the exact blocked message, retry send_message with user_approved=true.\n"
                + "10. Call complete_task only when the communication goal is genuinely complete.\n"
                + "11. The app UI shows the external conversation to the user, so do not narrate every message unless clarification is useful.";
    }

    private void handleServerMessage(String text) {
        if (!running) return;
        try {
            JSONObject response = new JSONObject(text);
            JSONObject apiError = response.optJSONObject("error");
            if (apiError != null) {
                fail(apiError.optString("message", "Gemini Live error"));
                return;
            }

            if (response.has("setupComplete") || response.has("setup_complete")) {
                setupReady = true;
                listener.onStatus("Listening");
                startAudio();
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
                        JSONObject call = calls.optJSONObject(i);
                        if (call == null) continue;
                        String id = call.optString("id", "call_" + i);
                        String name = call.optString("name", "");
                        JSONObject args = call.optJSONObject("args");
                        if (args == null) args = new JSONObject();
                        listener.onToolCall(id, name, args);
                    }
                }
            }
        } catch (Exception e) {
            listener.onError("Unable to parse Gemini Live event: " + e.getMessage());
        }
    }

    private void handleServerContent(JSONObject server) throws Exception {
        if (server.optBoolean("interrupted", false)) {
            flushPlayback();
            setSpeaking(false);
        }

        JSONObject input = server.optJSONObject("inputTranscription");
        if (input == null) input = server.optJSONObject("input_transcription");
        if (input != null) {
            String value = input.optString("text", "").trim();
            if (!value.isEmpty()) listener.onTranscript(value, "user");
        }

        JSONObject output = server.optJSONObject("outputTranscription");
        if (output == null) output = server.optJSONObject("output_transcription");
        if (output != null) {
            String value = output.optString("text", "").trim();
            if (!value.isEmpty()) listener.onTranscript(value, "mate");
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
                            setSpeaking(true);
                            audioWriter.execute(new Runnable() {
                                @Override public void run() { writeAudio(pcm); }
                            });
                        }
                    }
                }
            }
        }

        if (server.optBoolean("turnComplete", server.optBoolean("turn_complete", false))) {
            setSpeaking(false);
            listener.onStatus("Listening");
        }
    }

    private synchronized void startAudio() {
        if (!running || recording) return;
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail("Microphone permission is required.");
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

        recording = true;
        recorder.startRecording();
        micThread = new Thread(new Runnable() {
            @Override public void run() { captureLoop(); }
        }, "CrewMateMic");
        micThread.start();
    }

    private void captureLoop() {
        byte[] buffer = new byte[3200];
        while (running && recording && recorder != null) {
            int read = recorder.read(buffer, 0, buffer.length);
            if (read <= 0 || webSocket == null || !setupReady) continue;
            try {
                byte[] frame = new byte[read];
                System.arraycopy(buffer, 0, frame, 0, read);
                JSONObject audio = new JSONObject()
                        .put("mimeType", "audio/pcm;rate=16000")
                        .put("data", Base64.encodeToString(frame, Base64.NO_WRAP));
                JSONObject root = new JSONObject()
                        .put("realtimeInput", new JSONObject().put("audio", audio));
                webSocket.send(root.toString());
            } catch (Exception e) {
                if (running) listener.onError("Microphone stream error: " + e.getMessage());
            }
        }
    }

    private void writeAudio(byte[] pcm) {
        try {
            AudioTrack target = player;
            if (running && target != null) target.write(pcm, 0, pcm.length, AudioTrack.WRITE_BLOCKING);
        } catch (Exception ignored) {}
    }

    private synchronized void stopAudio() {
        recording = false;
        if (recorder != null) {
            try { recorder.stop(); } catch (Exception ignored) {}
            try { recorder.release(); } catch (Exception ignored) {}
            recorder = null;
        }
        if (player != null) {
            try { player.pause(); player.flush(); player.stop(); } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
        setSpeaking(false);
    }

    private void flushPlayback() {
        AudioTrack target = player;
        if (target == null) return;
        try { target.pause(); target.flush(); target.play(); } catch (Exception ignored) {}
    }

    private void setSpeaking(boolean value) {
        if (speaking == value) return;
        speaking = value;
        listener.onSpeakingChanged(value);
    }

    private void fail(String message) {
        running = false;
        stopAudio();
        listener.onError(message == null ? "Unknown Gemini Live error" : message);
    }

    private static String normalizeVoice(String value) {
        if (value == null) return "Kore";
        String v = value.trim();
        if ("Puck".equalsIgnoreCase(v)) return "Puck";
        if ("Charon".equalsIgnoreCase(v)) return "Charon";
        if ("Fenrir".equalsIgnoreCase(v)) return "Fenrir";
        if ("Aoede".equalsIgnoreCase(v)) return "Aoede";
        return "Kore";
    }
}
