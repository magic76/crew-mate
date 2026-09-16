package com.crewpocket.mate.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
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

import com.crewpocket.mate.agent.CrewMateRuntime;
import com.crewpocket.mate.channel.FakeMessagingBackend;
import com.crewpocket.mate.config.AppConfig;
import com.crewpocket.mate.model.CommunicationSession;
import com.crewpocket.mate.model.Message;
import com.crewpocket.mate.model.PendingApproval;
import com.crewpocket.mate.voice.GeminiLiveModelSession;

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
    private final int red = Color.rgb(248, 113, 113);

    private EditText apiKeyInput;
    private Button voiceButton;
    private TextView statusText;
    private TextView taskText;
    private TextView externalTranscript;
    private TextView privateTranscript;
    private LinearLayout approvalCard;
    private TextView approvalDraft;

    private CrewMateRuntime runtime;
    private GeminiLiveModelSession modelSession;
    private FakeMessagingBackend fakeBackend;
    private boolean pendingStartAfterPermission;
    private String editedApprovalText = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        setContentView(buildUi());
        apiKeyInput.setText(AppConfig.getApiKey(this));
        renderSession(null);
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
        subtitle.setText("Tell Mate what you want. External messages stay visible and every send requires your approval.");
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
        taskText.setBackground(roundRect(surface, 16));
        root.addView(taskText, cardLp(dp(12)));

        root.addView(sectionTitle("Mate ↔ other person"));
        externalTranscript = cardText(13);
        ScrollView externalScroll = new ScrollView(this);
        externalScroll.setFillViewport(true);
        externalScroll.addView(externalTranscript);
        externalScroll.setBackground(roundRect(surface, 16));
        LinearLayout.LayoutParams externalLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.05f);
        externalLp.setMargins(0, dp(6), 0, dp(10));
        root.addView(externalScroll, externalLp);

        approvalCard = buildApprovalCard();
        approvalCard.setVisibility(View.GONE);
        root.addView(approvalCard);

        root.addView(sectionTitle("You ↔ Mate · private"));
        privateTranscript = cardText(13);
        ScrollView privateScroll = new ScrollView(this);
        privateScroll.setFillViewport(true);
        privateScroll.addView(privateTranscript);
        privateScroll.setBackground(roundRect(surface, 16));
        LinearLayout.LayoutParams privateLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.75f);
        privateLp.setMargins(0, dp(6), 0, 0);
        root.addView(privateScroll, privateLp);
        return root;
    }

    private LinearLayout buildApprovalCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable background = roundRect(Color.rgb(61, 43, 18), 14);
        background.setStroke(dp(1), Color.rgb(180, 83, 9));
        card.setBackground(background);
        LinearLayout.LayoutParams lp = cardLp(dp(10));
        lp.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(lp);

        TextView label = new TextView(this);
        label.setText("Waiting for approval · not sent");
        label.setTextColor(amber);
        label.setTextSize(12);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(label);

        approvalDraft = new TextView(this);
        approvalDraft.setTextColor(text);
        approvalDraft.setTextSize(14);
        approvalDraft.setPadding(0, dp(8), 0, dp(10));
        card.addView(approvalDraft);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        Button edit = actionButton("Edit", surface2);
        edit.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { editApproval(); }
        });
        actions.addView(edit, new LinearLayout.LayoutParams(0, dp(42), 1f));

        Button approve = actionButton("Allow send", Color.rgb(5, 150, 105));
        approve.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (runtime != null && runtime.approvePending(editedApprovalText)) {
                    status("Sending", green);
                }
            }
        });
        LinearLayout.LayoutParams approveLp = new LinearLayout.LayoutParams(0, dp(42), 1.2f);
        approveLp.setMargins(dp(8), 0, 0, 0);
        actions.addView(approve, approveLp);

        Button cancel = actionButton("Cancel", Color.rgb(127, 29, 29));
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (runtime != null && runtime.cancelPending()) status("Needs your input", amber);
            }
        });
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(0, dp(42), 0.8f);
        cancelLp.setMargins(dp(8), 0, 0, 0);
        actions.addView(cancel, cancelLp);
        card.addView(actions);
        return card;
    }

    private void editApproval() {
        if (runtime == null || runtime.session().pendingApproval() == null) return;
        final EditText input = new EditText(this);
        input.setText(editedApprovalText);
        input.setSelection(input.getText().length());
        input.setTextColor(Color.BLACK);
        new AlertDialog.Builder(this)
                .setTitle("Edit message before sending")
                .setView(input)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        editedApprovalText = input.getText().toString().trim();
                        approvalDraft.setText(editedApprovalText);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void toggleVoice() {
        if (runtime != null) {
            stopRuntime();
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
        startRuntime(key);
    }

    private void startRuntime(String key) {
        fakeBackend = new FakeMessagingBackend();
        modelSession = new GeminiLiveModelSession(this, key, AppConfig.getVoice(this),
                new GeminiLiveModelSession.UiListener() {
                    @Override public void onStatus(final String value) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                if ("Listening".equals(value)) status("Listening", green);
                            }
                        });
                    }

                    @Override public void onInputTranscript(final String value) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                if (runtime != null) runtime.recordUserTranscript(value);
                            }
                        });
                    }

                    @Override public void onSpeakingChanged(final boolean speaking) {
                        if (speaking) runOnUiThread(new Runnable() {
                            @Override public void run() { status("Mate is speaking…", accent); }
                        });
                    }

                    @Override public void onError(final String message) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() { status(message, red); }
                        });
                    }
                });

        runtime = new CrewMateRuntime(modelSession, fakeBackend, new CrewMateRuntime.Listener() {
            @Override public void onSessionChanged(final CommunicationSession session) {
                runOnUiThread(new Runnable() {
                    @Override public void run() { renderSession(session); }
                });
            }

            @Override public void onApprovalRequired(final CommunicationSession session, final PendingApproval approval) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        editedApprovalText = approval.content();
                        renderSession(session);
                    }
                });
            }

            @Override public void onRuntimeStatus(final String value) {
                runOnUiThread(new Runnable() {
                    @Override public void run() { status(value, statusColor(value)); }
                });
            }
        });

        voiceButton.setText("End");
        status("Connecting…", amber);
        runtime.start();
    }

    private void stopRuntime() {
        CrewMateRuntime target = runtime;
        runtime = null;
        if (target != null) target.close();
        if (fakeBackend != null) fakeBackend.shutdown();
        fakeBackend = null;
        modelSession = null;
        voiceButton.setText("Start");
        status("Stopped", muted);
    }

    private void renderSession(CommunicationSession session) {
        if (session == null) {
            taskText.setText("No active communication session\nTry: 幫我問 John 明天晚上有沒有空吃飯");
            externalTranscript.setText("Mate → other person and replies will appear here.");
            privateTranscript.setText("Your instructions to Mate stay here and are never copied into the external timeline.");
            approvalCard.setVisibility(View.GONE);
            return;
        }

        String person = session.targetPerson().isEmpty() ? "Finding contact…" : session.targetPerson();
        String goal = session.goal().isEmpty() ? "Understanding your goal…" : session.goal();
        taskText.setText(person + "\n" + goal + "\n" + CrewMateRuntime.displayStatus(session.status()));

        StringBuilder external = new StringBuilder();
        StringBuilder privateChat = new StringBuilder();
        for (Message message : session.messages()) {
            if (message.sender == Message.Sender.OTHER_PERSON
                    || (message.sender == Message.Sender.MATE && !"USER".equals(message.recipient))) {
                if (message.sender == Message.Sender.MATE) {
                    external.append("Mate → ").append(person);
                } else {
                    external.append(person).append(" → Mate");
                }
                if (message.status() == Message.Status.DRAFT) external.append(" · DRAFT");
                if (message.status() == Message.Status.PENDING_APPROVAL) external.append(" · NOT SENT");
                external.append("\n").append(message.content()).append("\n\n");
            } else if (message.sender == Message.Sender.USER || message.sender == Message.Sender.MATE) {
                privateChat.append(message.sender == Message.Sender.USER ? "You" : "Mate")
                        .append("\n").append(message.content()).append("\n\n");
            }
        }
        externalTranscript.setText(external.length() == 0 ? "Preparing conversation…" : external.toString());
        privateTranscript.setText(privateChat.length() == 0 ? "Speak naturally to Mate." : privateChat.toString());

        PendingApproval approval = session.pendingApproval();
        if (approval != null && approval.state() == PendingApproval.State.WAITING) {
            if (editedApprovalText.isEmpty()) editedApprovalText = approval.content();
            approvalDraft.setText(editedApprovalText);
            approvalCard.setVisibility(View.VISIBLE);
        } else {
            editedApprovalText = "";
            approvalCard.setVisibility(View.GONE);
        }
    }

    private int statusColor(String value) {
        if (value == null) return muted;
        if (value.contains("approval") || value.contains("input") || value.contains("Connecting")) return amber;
        if (value.contains("Sending") || value.contains("Listening") || value.contains("reply")) return green;
        if (value.contains("Error")) return red;
        return muted;
    }

    private void status(String value, int color) {
        statusText.setText(value == null ? "" : value);
        statusText.setTextColor(color);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_AUDIO) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED && pendingStartAfterPermission) {
            pendingStartAfterPermission = false;
            startRuntime(AppConfig.getApiKey(this));
        } else {
            pendingStartAfterPermission = false;
            Toast.makeText(this, "Microphone permission is required for Crew Mate voice.", Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onDestroy() {
        if (runtime != null) stopRuntime();
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

    private Button actionButton(String label, int color) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(11);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setBackground(roundRect(color, 10));
        return button;
    }

    private LinearLayout.LayoutParams cardLp(int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(bottom));
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
