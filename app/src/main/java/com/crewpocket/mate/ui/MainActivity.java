package com.crewpocket.mate.ui;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.crewpocket.mate.agent.MateAgent;
import com.crewpocket.mate.channel.MockChannel;
import com.crewpocket.mate.config.AppConfig;
import com.crewpocket.mate.model.CommunicationTask;
import com.crewpocket.mate.model.Message;
import com.crewpocket.mate.voice.MateLiveClient;

import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final int REQUEST_AUDIO = 701;

    private final int bg = Color.rgb(9, 15, 31);
    private final int surface = Color.rgb(17, 25, 47);
    private final int surface2 = Color.rgb(24, 34, 61);
    private final int text = Color.rgb(241, 245, 249);
    private final int muted = Color.rgb(148, 163, 184);
    private final int accent = Color.rgb(124, 140, 255);
    private final int green = Color.rgb(52, 211, 153);
    private final int amber = Color.rgb(251, 191, 36);

    private EditText apiKeyInput;
    private Button voiceButton;
    private Button approveButton;
    private TextView statusText;
    private TextView taskText;
    private TextView privateTranscript;
    private TextView externalTranscript;

    private final StringBuilder privateLog = new StringBuilder();
    private MateLiveClient liveClient;
    private MateAgent agent;
    private boolean pendingStartAfterPermission;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);

        agent = new MateAgent(new MockChannel(), new MateAgent.Listener() {
            @Override public void onTaskChanged(final CommunicationTask task) {
                runOnUiThread(new Runnable() {
                    @Override public void run() { renderTask(task); }
                });
            }

            @Override public void onApprovalRequired(final CommunicationTask task, final String draft, final String reason) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        approveButton.setVisibility(View.VISIBLE);
                        statusText.setText("Needs your approval · " + reason);
                        statusText.setTextColor(amber);
                    }
                });
            }

            @Override public void onAgentEvent(final String event) {
                runOnUiThread(new Runnable() {
                    @Override public void run() { statusText.setText(event); }
                });
            }
        });
        agent.setContextSink(new MateAgent.ContextSink() {
            @Override public void pushContext(String value) {
                MateLiveClient client = liveClient;
                if (client != null) client.pushContext(value);
            }
        });

        setContentView(buildUi());
        apiKeyInput.setText(AppConfig.getApiKey(this));
        renderTask(null);
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(18));
        root.setBackgroundColor(bg);

        TextView title = new TextView(this);
        title.setText("Crew Mate");
        title.setTextSize(26);
        title.setTextColor(text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Tell me what you want. I’ll handle the conversation — and show you everything I send and receive.");
        subtitle.setTextSize(12);
        subtitle.setTextColor(muted);
        subtitle.setPadding(0, dp(4), 0, dp(14));
        root.addView(subtitle);

        LinearLayout keyRow = new LinearLayout(this);
        keyRow.setOrientation(LinearLayout.HORIZONTAL);
        keyRow.setGravity(Gravity.CENTER_VERTICAL);

        apiKeyInput = new EditText(this);
        apiKeyInput.setSingleLine(true);
        apiKeyInput.setHint("Gemini API key");
        apiKeyInput.setHintTextColor(Color.rgb(100, 116, 139));
        apiKeyInput.setTextColor(text);
        apiKeyInput.setTextSize(13);
        apiKeyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiKeyInput.setBackground(roundRect(surface2, 12));
        apiKeyInput.setPadding(dp(12), 0, dp(12), 0);
        keyRow.addView(apiKeyInput, new LinearLayout.LayoutParams(0, dp(46), 1f));

        voiceButton = new Button(this);
        voiceButton.setText("Start");
        voiceButton.setTextColor(Color.WHITE);
        voiceButton.setTextSize(12);
        voiceButton.setAllCaps(false);
        voiceButton.setBackground(roundRect(accent, 12));
        LinearLayout.LayoutParams voiceLp = new LinearLayout.LayoutParams(dp(86), dp(46));
        voiceLp.setMargins(dp(10), 0, 0, 0);
        keyRow.addView(voiceButton, voiceLp);
        voiceButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleVoice(); }
        });
        root.addView(keyRow);

        statusText = new TextView(this);
        statusText.setText("Ready");
        statusText.setTextColor(muted);
        statusText.setTextSize(12);
        statusText.setPadding(0, dp(10), 0, dp(10));
        root.addView(statusText);

        taskText = cardText(13);
        taskText.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(taskText, cardLp(dp(10)));

        LinearLayout conversationHeader = new LinearLayout(this);
        conversationHeader.setOrientation(LinearLayout.HORIZONTAL);
        conversationHeader.setGravity(Gravity.CENTER_VERTICAL);

        TextView extTitle = sectionTitle("External conversation");
        conversationHeader.addView(extTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        approveButton = new Button(this);
        approveButton.setText("Approve draft");
        approveButton.setTextSize(11);
        approveButton.setTextColor(Color.WHITE);
        approveButton.setAllCaps(false);
        approveButton.setBackground(roundRect(Color.rgb(180, 83, 9), 10));
        approveButton.setVisibility(View.GONE);
        approveButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                CommunicationTask task = agent.getCurrentTask();
                if (task == null || task.pendingDraft == null || task.pendingDraft.trim().isEmpty()) return;
                if (liveClient != null) {
                    liveClient.pushContext("USER_EVENT: The user explicitly approved this exact blocked draft: " + task.pendingDraft
                            + "\nRetry send_message with the exact same text and user_approved=true.");
                    approveButton.setVisibility(View.GONE);
                    statusText.setText("Approval sent to Mate");
                    statusText.setTextColor(green);
                }
            }
        });
        conversationHeader.addView(approveButton, new LinearLayout.LayoutParams(dp(118), dp(40)));
        root.addView(conversationHeader);

        externalTranscript = cardText(13);
        ScrollView externalScroll = new ScrollView(this);
        externalScroll.setFillViewport(true);
        externalScroll.addView(externalTranscript);
        LinearLayout.LayoutParams externalLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.15f);
        externalLp.setMargins(0, dp(6), 0, dp(12));
        externalScroll.setLayoutParams(externalLp);
        externalScroll.setBackground(roundRect(surface, 16));
        root.addView(externalScroll);

        root.addView(sectionTitle("Private voice conversation"));

        privateTranscript = cardText(13);
        ScrollView privateScroll = new ScrollView(this);
        privateScroll.setFillViewport(true);
        privateScroll.addView(privateTranscript);
        LinearLayout.LayoutParams privateLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.85f);
        privateLp.setMargins(0, dp(6), 0, 0);
        privateScroll.setLayoutParams(privateLp);
        privateScroll.setBackground(roundRect(surface, 16));
        root.addView(privateScroll);

        return root;
    }

    private void toggleVoice() {
        if (liveClient != null && liveClient.isRunning()) {
            liveClient.stop();
            liveClient = null;
            voiceButton.setText("Start");
            statusText.setText("Stopped");
            statusText.setTextColor(muted);
            return;
        }

        String key = apiKeyInput.getText().toString().trim();
        if (key.isEmpty()) {
            Toast.makeText(this, "Enter your Gemini API key first.", Toast.LENGTH_SHORT).show();
            return;
        }
        AppConfig.setApiKey(this, key);

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingStartAfterPermission = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO);
            return;
        }
        startVoice(key);
    }

    private void startVoice(String key) {
        privateLog.setLength(0);
        privateTranscript.setText("Speak naturally. For example:\n\"幫我跟 Kevin 約明天下午的 meeting，最好三點以後。\"\n\n");
        statusText.setText("Connecting…");
        statusText.setTextColor(amber);

        liveClient = new MateLiveClient(this, key, AppConfig.getVoice(this), new MateLiveClient.Listener() {
            @Override public void onStatus(final String value) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        statusText.setText(value);
                        statusText.setTextColor("Listening".equals(value) ? green : muted);
                    }
                });
            }

            @Override public void onTranscript(final String value, final String role) {
                runOnUiThread(new Runnable() {
                    @Override public void run() { appendPrivate(role, value); }
                });
            }

            @Override public void onToolCall(final String id, final String name, final JSONObject args) {
                agent.handleToolCall(name, args, new MateAgent.ToolResultCallback() {
                    @Override public void onResult(JSONObject result) {
                        MateLiveClient client = liveClient;
                        if (client != null) client.sendToolResponse(id, name, result);
                    }
                });
            }

            @Override public void onSpeakingChanged(final boolean speaking) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (speaking) {
                            statusText.setText("Mate is speaking…");
                            statusText.setTextColor(accent);
                        }
                    }
                });
            }

            @Override public void onError(final String message) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        statusText.setText(message);
                        statusText.setTextColor(Color.rgb(248, 113, 113));
                        voiceButton.setText("Start");
                    }
                });
            }
        });
        voiceButton.setText("End");
        liveClient.start();
    }

    private void appendPrivate(String role, String value) {
        if (value == null || value.trim().isEmpty()) return;
        String who = "user".equalsIgnoreCase(role) ? "You" : "Mate";
        privateLog.append(who).append("\n").append(value.trim()).append("\n\n");
        privateTranscript.setText(privateLog.toString());
    }

    private void renderTask(CommunicationTask task) {
        if (task == null) {
            taskText.setText("No active task\nTell Crew Mate who to talk to and what outcome you want.");
            externalTranscript.setText("External messages will appear here.\nYour private instructions never appear in this channel.");
            approveButton.setVisibility(View.GONE);
            return;
        }

        StringBuilder header = new StringBuilder();
        header.append(task.contact).append("\n")
                .append(task.goal).append("\n")
                .append("State: ").append(task.state.name());
        if (!task.privateContext.isEmpty()) {
            header.append("\nPrivate context: ");
            for (int i = 0; i < task.privateContext.size(); i++) {
                if (i > 0) header.append(" · ");
                header.append(task.privateContext.get(i));
            }
        }
        if (task.state == CommunicationTask.State.COMPLETED && !task.summary.isEmpty()) {
            header.append("\nOutcome: ").append(task.summary);
        }
        taskText.setText(header.toString());

        StringBuilder ext = new StringBuilder();
        for (Message message : task.snapshotMessages()) {
            if (message.sender == Message.Sender.MATE) {
                ext.append("Mate → ").append(task.contact).append("\n");
            } else {
                ext.append(task.contact).append("\n");
            }
            ext.append(message.text).append("\n\n");
        }
        if (task.pendingDraft != null && !task.pendingDraft.trim().isEmpty()) {
            ext.append("DRAFT · not sent\n").append(task.pendingDraft).append("\n");
        }
        if (ext.length() == 0) ext.append("Preparing the first message…");
        externalTranscript.setText(ext.toString());

        if (task.state != CommunicationTask.State.NEEDS_USER) approveButton.setVisibility(View.GONE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_AUDIO) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED && pendingStartAfterPermission) {
            pendingStartAfterPermission = false;
            startVoice(AppConfig.getApiKey(this));
        } else {
            pendingStartAfterPermission = false;
            Toast.makeText(this, "Microphone permission is required for Crew Mate voice.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        if (liveClient != null) liveClient.stop();
        super.onDestroy();
    }

    private TextView sectionTitle(String value) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(12);
        view.setTextColor(muted);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private TextView cardText(int size) {
        TextView view = new TextView(this);
        view.setTextColor(text);
        view.setTextSize(size);
        view.setLineSpacing(0, 1.18f);
        view.setPadding(dp(14), dp(12), dp(14), dp(12));
        return view;
    }

    private LinearLayout.LayoutParams cardLp(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(top));
        return lp;
    }

    private GradientDrawable roundRect(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
