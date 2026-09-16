package com.crewpocket.mate.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import com.crewpocket.mate.channel.MessagingBackend;
import com.crewpocket.mate.channel.TelegramMessagingBackend;
import com.crewpocket.mate.config.AppConfig;
import com.crewpocket.mate.model.CommunicationSession;
import com.crewpocket.mate.model.Message;
import com.crewpocket.mate.model.PendingApproval;
import com.crewpocket.mate.service.CommunicationContinuationService;
import com.crewpocket.mate.storage.SessionStore;
import com.crewpocket.mate.voice.GeminiLiveModelSession;
import com.crewpocket.mate.voice.TurnTextAccumulator;

import java.util.List;

/**
 * Crew Mate is intentionally task-first, not chat-first.
 * The user privately briefs Mate, then watches Mate carry the external conversation forward.
 */
public class MainActivity extends Activity {
    private static final int REQUEST_AUDIO = 701;
    private static final int REQUEST_NOTIFICATIONS = 702;
    private static final long INPUT_TRANSCRIPT_SETTLE_MS = 900L;

    private final int bg = Color.rgb(9, 15, 31);
    private final int surface = Color.rgb(17, 25, 47);
    private final int surface2 = Color.rgb(24, 34, 61);
    private final int text = Color.rgb(241, 245, 249);
    private final int muted = Color.rgb(148, 163, 184);
    private final int accent = Color.rgb(124, 140, 255);
    private final int accentSurface = Color.rgb(48, 55, 103);
    private final int inboundSurface = Color.rgb(25, 39, 65);
    private final int privateUserSurface = Color.rgb(29, 64, 105);
    private final int green = Color.rgb(52, 211, 153);
    private final int amber = Color.rgb(251, 191, 36);
    private final int red = Color.rgb(248, 113, 113);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TurnTextAccumulator inputTurn = new TurnTextAccumulator();
    private final Runnable flushInputTurnRunnable = new Runnable() {
        @Override public void run() { flushUserInputTurn(); }
    };

    private Button voiceButton;
    private Button settingsButton;
    private TextView statusText;
    private TextView taskText;
    private TextView externalSectionTitle;
    private TextView privateSectionTitle;
    private LinearLayout externalTimeline;
    private LinearLayout privateTimeline;
    private ScrollView externalScroll;
    private ScrollView privateScroll;
    private LinearLayout approvalCard;
    private TextView approvalLabel;
    private TextView approvalDraft;

    private CrewMateRuntime runtime;
    private GeminiLiveModelSession modelSession;
    private MessagingBackend messagingBackend;
    private SessionStore sessionStore;
    private CommunicationSession viewedSession;
    private boolean pendingStartAfterPermission;
    private String editedApprovalText = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        sessionStore = new SessionStore(this);
        setContentView(buildUi());
        restoreRequestedOrLatest(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        restoreRequestedOrLatest(intent);
    }

    private void restoreRequestedOrLatest(Intent intent) {
        CommunicationSession restored = null;
        if (intent != null) {
            String requested = intent.getStringExtra(CommunicationContinuationService.EXTRA_SESSION_ID);
            if (requested != null && !requested.trim().isEmpty()) {
                restored = sessionStore.loadById(requested.trim());
            }
        }
        if (restored == null) restored = sessionStore.loadLatest();
        viewedSession = restored;
        renderSession(viewedSession);
        if (viewedSession == null) {
            status("Ready", muted);
        } else if (viewedSession.status() == CommunicationSession.Status.REPLY_RECEIVED) {
            status("Reply received", green);
        } else {
            status(CrewMateRuntime.displayStatus(viewedSession.status()), statusColor(CrewMateRuntime.displayStatus(viewedSession.status())));
        }
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(16));
        root.setBackgroundColor(bg);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("Crew Mate");
        title.setTextSize(26);
        title.setTextColor(text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleBox.addView(title);
        TextView subtitle = new TextView(this);
        subtitle.setText("先交代目標，Mate 會替你把對話談完。");
        subtitle.setTextSize(12);
        subtitle.setTextColor(muted);
        subtitle.setPadding(0, dp(2), 0, 0);
        titleBox.addView(subtitle);
        top.addView(titleBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        settingsButton = actionButton("設定", surface2);
        settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSettings(); }
        });
        top.addView(settingsButton, new LinearLayout.LayoutParams(dp(72), dp(38)));
        root.addView(top);

        statusText = new TextView(this);
        statusText.setText("Ready");
        statusText.setTextColor(muted);
        statusText.setTextSize(12);
        statusText.setTypeface(Typeface.DEFAULT_BOLD);
        statusText.setPadding(dp(12), dp(8), dp(12), dp(8));
        statusText.setBackground(roundRect(surface2, 12));
        LinearLayout.LayoutParams statusLp = cardLp(dp(10));
        statusLp.setMargins(0, dp(14), 0, dp(10));
        root.addView(statusText, statusLp);

        taskText = cardText(14);
        taskText.setBackground(roundRect(surface, 18));
        root.addView(taskText, cardLp(dp(12)));

        externalSectionTitle = sectionTitle("Mate 對外溝通");
        root.addView(externalSectionTitle);
        externalTimeline = new LinearLayout(this);
        externalTimeline.setOrientation(LinearLayout.VERTICAL);
        externalTimeline.setPadding(dp(10), dp(10), dp(10), dp(10));
        externalScroll = new ScrollView(this);
        externalScroll.setFillViewport(true);
        externalScroll.addView(externalTimeline);
        externalScroll.setBackground(roundRect(surface, 18));
        LinearLayout.LayoutParams externalLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.2f);
        externalLp.setMargins(0, dp(6), 0, dp(10));
        root.addView(externalScroll, externalLp);

        approvalCard = buildApprovalCard();
        approvalCard.setVisibility(View.GONE);
        root.addView(approvalCard);

        privateSectionTitle = sectionTitle("只給 Mate 的私人交代");
        root.addView(privateSectionTitle);
        privateTimeline = new LinearLayout(this);
        privateTimeline.setOrientation(LinearLayout.VERTICAL);
        privateTimeline.setPadding(dp(10), dp(8), dp(10), dp(8));
        privateScroll = new ScrollView(this);
        privateScroll.setFillViewport(true);
        privateScroll.addView(privateTimeline);
        privateScroll.setBackground(roundRect(surface, 18));
        LinearLayout.LayoutParams privateLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.5f);
        privateLp.setMargins(0, dp(6), 0, dp(10));
        root.addView(privateScroll, privateLp);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);

        voiceButton = actionButton("交代給 Mate", accent);
        voiceButton.setTextSize(13);
        voiceButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleVoice(); }
        });
        actions.addView(voiceButton, new LinearLayout.LayoutParams(0, dp(50), 1.5f));

        Button newTask = actionButton("新任務", surface2);
        newTask.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startFreshTask(); }
        });
        LinearLayout.LayoutParams newLp = new LinearLayout.LayoutParams(0, dp(50), 0.8f);
        newLp.setMargins(dp(8), 0, 0, 0);
        actions.addView(newTask, newLp);

        Button history = actionButton("紀錄", surface2);
        history.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showHistory(); }
        });
        LinearLayout.LayoutParams historyLp = new LinearLayout.LayoutParams(0, dp(50), 0.7f);
        historyLp.setMargins(dp(8), 0, 0, 0);
        actions.addView(history, historyLp);
        root.addView(actions);

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

        approvalLabel = new TextView(this);
        approvalLabel.setText("Mate 準備送出這則訊息");
        approvalLabel.setTextColor(amber);
        approvalLabel.setTextSize(12);
        approvalLabel.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(approvalLabel);

        approvalDraft = new TextView(this);
        approvalDraft.setTextColor(text);
        approvalDraft.setTextSize(14);
        approvalDraft.setLineSpacing(0, 1.15f);
        approvalDraft.setPadding(0, dp(8), 0, dp(10));
        card.addView(approvalDraft);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        Button edit = actionButton("修改", surface2);
        edit.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { editApproval(); }
        });
        actions.addView(edit, new LinearLayout.LayoutParams(0, dp(42), 1f));

        Button approve = actionButton("允許送出", Color.rgb(5, 150, 105));
        approve.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (runtime != null && runtime.approvePending(editedApprovalText)) status("Sending", green);
            }
        });
        LinearLayout.LayoutParams approveLp = new LinearLayout.LayoutParams(0, dp(42), 1.25f);
        approveLp.setMargins(dp(8), 0, 0, 0);
        actions.addView(approve, approveLp);

        Button cancel = actionButton("取消", Color.rgb(127, 29, 29));
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
                .setTitle("送出前修改")
                .setView(input)
                .setPositiveButton("儲存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        editedApprovalText = input.getText().toString().trim();
                        approvalDraft.setText(editedApprovalText);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showSettings() {
        if (runtime != null) {
            Toast.makeText(this, "請先結束目前的語音 session 再修改設定。", Toast.LENGTH_SHORT).show();
            return;
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(4), dp(22), 0);

        final EditText api = dialogInput("Gemini API key", true);
        api.setText(AppConfig.getApiKey(this));
        box.addView(api, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));

        final boolean[] telegram = new boolean[]{
                AppConfig.PROVIDER_TELEGRAM.equals(AppConfig.getMessagingProvider(this))
        };
        final Button provider = actionButton(telegram[0] ? "Provider: Telegram" : "Provider: Fake", Color.rgb(71, 85, 105));
        LinearLayout.LayoutParams providerLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        providerLp.setMargins(0, dp(10), 0, 0);
        box.addView(provider, providerLp);

        final EditText token = dialogInput("Telegram bot token", true);
        token.setText(AppConfig.getTelegramBotToken(this));
        token.setVisibility(telegram[0] ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams tokenLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        tokenLp.setMargins(0, dp(10), 0, 0);
        box.addView(token, tokenLp);

        TextView note = new TextView(this);
        note.setText("Telegram 對象必須先跟 Bot 傳過 /start，Mate 才能找到並與他對話。");
        note.setTextColor(Color.DKGRAY);
        note.setTextSize(11);
        note.setPadding(0, dp(8), 0, 0);
        box.addView(note);

        provider.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                telegram[0] = !telegram[0];
                provider.setText(telegram[0] ? "Provider: Telegram" : "Provider: Fake");
                token.setVisibility(telegram[0] ? View.VISIBLE : View.GONE);
            }
        });

        new AlertDialog.Builder(this)
                .setTitle("Crew Mate 設定")
                .setView(box)
                .setPositiveButton("儲存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        AppConfig.setApiKey(MainActivity.this, api.getText().toString());
                        AppConfig.setTelegramBotToken(MainActivity.this, token.getText().toString());
                        AppConfig.setMessagingProvider(MainActivity.this,
                                telegram[0] ? AppConfig.PROVIDER_TELEGRAM : AppConfig.PROVIDER_FAKE);
                        renderSession(viewedSession);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private EditText dialogInput(String hint, boolean secret) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setTextColor(Color.BLACK);
        input.setHintTextColor(Color.GRAY);
        if (secret) input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return input;
    }

    private void startFreshTask() {
        if (runtime != null) stopRuntime();
        viewedSession = null;
        editedApprovalText = "";
        renderSession(null);
        status("Ready", muted);
    }

    private void showHistory() {
        if (runtime != null) {
            Toast.makeText(this, "請先結束目前的語音 session 再查看紀錄。", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<CommunicationSession> sessions = sessionStore.loadAll();
        if (sessions.isEmpty()) {
            Toast.makeText(this, "目前還沒有溝通紀錄。", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[sessions.size()];
        for (int i = 0; i < sessions.size(); i++) {
            CommunicationSession session = sessions.get(i);
            String person = session.targetPerson().isEmpty() ? "未指定對象" : session.targetPerson();
            String goal = session.goal().isEmpty() ? "尚未確認目標" : session.goal();
            labels[i] = person + " · " + goal + " · " + friendlyStatus(CrewMateRuntime.displayStatus(session.status()));
        }
        new AlertDialog.Builder(this)
                .setTitle("溝通紀錄")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        viewedSession = sessions.get(which);
                        renderSession(viewedSession);
                        status(CrewMateRuntime.displayStatus(viewedSession.status()), statusColor(CrewMateRuntime.displayStatus(viewedSession.status())));
                    }
                })
                .setNegativeButton("關閉", null)
                .show();
    }

    private void toggleVoice() {
        if (runtime != null) {
            stopRuntime();
            return;
        }

        String key = AppConfig.getApiKey(this);
        if (key.isEmpty()) {
            Toast.makeText(this, "先到設定填入 Gemini API key。", Toast.LENGTH_SHORT).show();
            showSettings();
            return;
        }
        if (AppConfig.PROVIDER_TELEGRAM.equals(AppConfig.getMessagingProvider(this))
                && AppConfig.getTelegramBotToken(this).isEmpty()) {
            Toast.makeText(this, "Telegram 模式需要 Bot token。", Toast.LENGTH_LONG).show();
            showSettings();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingStartAfterPermission = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO);
            return;
        }
        startRuntime(key);
    }

    private MessagingBackend createMessagingBackend() {
        if (AppConfig.PROVIDER_TELEGRAM.equals(AppConfig.getMessagingProvider(this))) {
            return new TelegramMessagingBackend(this, AppConfig.getTelegramBotToken(this));
        }
        return new FakeMessagingBackend();
    }

    private void startRuntime(String key) {
        inputTurn.clear();
        handler.removeCallbacks(flushInputTurnRunnable);

        final CommunicationSession liveSession;
        final boolean resumeExisting = shouldResume(viewedSession);
        final CommunicationSession.Status resumeStatus = resumeExisting ? viewedSession.status() : null;
        final boolean resumeAfterReply = resumeExisting
                && (resumeStatus == CommunicationSession.Status.REPLY_RECEIVED
                || resumeStatus == CommunicationSession.Status.STOPPED);
        if (resumeExisting) {
            liveSession = viewedSession;
        } else {
            liveSession = new CommunicationSession();
            viewedSession = liveSession;
            sessionStore.save(liveSession);
        }

        stopService(new Intent(this, CommunicationContinuationService.class));
        messagingBackend = createMessagingBackend();

        modelSession = new GeminiLiveModelSession(this, key, AppConfig.getVoice(this), new GeminiLiveModelSession.UiListener() {
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
                        inputTurn.append(value);
                        handler.removeCallbacks(flushInputTurnRunnable);
                        handler.postDelayed(flushInputTurnRunnable, INPUT_TRANSCRIPT_SETTLE_MS);
                    }
                });
            }

            @Override public void onSpeakingChanged(final boolean speaking) {
                if (speaking) runOnUiThread(new Runnable() {
                    @Override public void run() {
                        flushUserInputTurn();
                        status("Mate is speaking…", accent);
                    }
                });
            }

            @Override public void onError(final String message) {
                runOnUiThread(new Runnable() {
                    @Override public void run() { status(message, red); }
                });
            }
        });

        runtime = new CrewMateRuntime(liveSession, modelSession, messagingBackend, new CrewMateRuntime.Listener() {
            @Override public void onSessionChanged(final CommunicationSession session) {
                sessionStore.save(session);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        viewedSession = session;
                        renderSession(session);
                    }
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
                    @Override public void run() {
                        if (value != null && !"Thinking".equals(value)) flushUserInputTurn();
                        status(value, statusColor(value));
                    }
                });
            }
        });

        voiceButton.setText("結束語音");
        status("Connecting…", amber);
        renderSession(liveSession);
        runtime.start();
        if (resumeAfterReply) {
            runtime.resumePersistedTask();
        } else if (resumeExisting
                && resumeStatus == CommunicationSession.Status.WAITING_FOR_REPLY
                && AppConfig.PROVIDER_TELEGRAM.equals(AppConfig.getMessagingProvider(this))) {
            watchReplyWhileRuntimeIsActive(liveSession);
        }
    }

    private void watchReplyWhileRuntimeIsActive(final CommunicationSession session) {
        final MessagingBackend backend = messagingBackend;
        final CrewMateRuntime activeRuntime = runtime;
        if (backend == null || activeRuntime == null || session == null || session.targetPersonId().isEmpty()) return;
        MessagingBackend.Contact contact = new MessagingBackend.Contact(session.targetPersonId(), session.targetPerson());
        backend.watchIncoming(contact, session.latestMessageTimestamp(), new MessagingBackend.IncomingCallback() {
            @Override public void onMessage(MessagingBackend.RemoteMessage reply) {
                CrewMateRuntime current = runtime;
                if (current != null && current == activeRuntime) current.acceptExternalReply(reply);
            }

            @Override public void onError(final String message) {
                if (runtime != activeRuntime) return;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        status(message == null || message.trim().isEmpty() ? "Waiting for reply" : message, red);
                    }
                });
            }
        });
    }

    private boolean shouldResume(CommunicationSession session) {
        if (session == null || session.targetPersonId().isEmpty()) return false;
        return session.status() != CommunicationSession.Status.COMPLETED
                && session.status() != CommunicationSession.Status.ERROR;
    }

    private void flushUserInputTurn() {
        handler.removeCallbacks(flushInputTurnRunnable);
        String completed = inputTurn.take();
        if (completed.isEmpty()) return;
        CrewMateRuntime target = runtime;
        if (target != null) target.recordUserTranscript(completed);
    }

    private void stopRuntime() {
        flushUserInputTurn();
        CrewMateRuntime target = runtime;
        CommunicationSession session = target == null ? viewedSession : target.session();
        boolean shouldHandoff = shouldHandoffToBackground(session);

        if (session != null) sessionStore.save(session);
        if (target != null) target.close();
        runtime = null;
        if (messagingBackend != null) messagingBackend.shutdown();
        messagingBackend = null;
        modelSession = null;

        if (session != null) {
            viewedSession = session;
            sessionStore.save(session);
        }
        if (shouldHandoff && session != null) {
            CommunicationContinuationService.start(this, session.sessionId);
            requestNotificationPermissionIfUseful();
            status("Waiting for reply", green);
        } else {
            status(session == null ? "Ready" : CrewMateRuntime.displayStatus(session.status()), muted);
        }
        renderSession(viewedSession);
    }

    private boolean shouldHandoffToBackground(CommunicationSession session) {
        return session != null
                && session.status() == CommunicationSession.Status.WAITING_FOR_REPLY
                && session.delegationAuthorized()
                && AppConfig.PROVIDER_TELEGRAM.equals(AppConfig.getMessagingProvider(this))
                && !AppConfig.getTelegramBotToken(this).isEmpty();
    }

    private void requestNotificationPermissionIfUseful() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }

    private void renderSession(CommunicationSession session) {
        boolean active = session != null;
        externalSectionTitle.setVisibility(active ? View.VISIBLE : View.GONE);
        externalScroll.setVisibility(active ? View.VISIBLE : View.GONE);
        privateSectionTitle.setVisibility(active ? View.VISIBLE : View.GONE);
        privateScroll.setVisibility(active ? View.VISIBLE : View.GONE);

        if (!active) {
            taskText.setText("想讓 Mate 幫你處理什麼？\n\n例如：\n「幫我問 John 明天晚上有沒有空吃飯，如果可以的話就幫我把時間約好。」\n\n你只需要交代目標，後面的溝通由 Mate 接手。");
            approvalCard.setVisibility(View.GONE);
            voiceButton.setText("交代給 Mate");
            return;
        }

        String person = session.targetPerson().isEmpty() ? "正在找對象…" : session.targetPerson();
        String goal = session.goal().isEmpty() ? "正在理解你的目標…" : session.goal();
        String delegation = session.delegationAuthorized()
                ? "已授權：Mate 可在這個目標內直接處理一般往返"
                : "第一則對外訊息會先讓你確認";
        StringBuilder task = new StringBuilder();
        task.append(person).append("\n")
                .append(goal).append("\n\n")
                .append(delegation);
        if (!session.pendingUserQuestion().isEmpty()) {
            task.append("\n\n需要你決定：").append(session.pendingUserQuestion());
        }
        if (!session.outcomeSummary().isEmpty()) {
            task.append("\n\n結果：").append(session.outcomeSummary());
        }
        taskText.setText(task.toString());

        externalSectionTitle.setText("Mate 正在和 " + person + " 溝通");
        privateSectionTitle.setText("只給 Mate 的私人交代");
        renderTimelines(session, person);

        PendingApproval approval = session.pendingApproval();
        if (runtime != null && approval != null && approval.state() == PendingApproval.State.WAITING) {
            if (editedApprovalText.isEmpty()) editedApprovalText = approval.content();
            approvalDraft.setText(editedApprovalText);
            if (approval.reason.contains("ASK_FIRST_MESSAGE")) {
                approvalLabel.setText("確認第一則訊息 · 通過後 Mate 會在此目標內自行往返");
            } else if (approval.reason.contains("HIGH_RISK")) {
                approvalLabel.setText("這一步可能產生承諾或風險，需要你確認");
            } else {
                approvalLabel.setText("Mate 準備送出這則訊息");
            }
            approvalCard.setVisibility(View.VISIBLE);
        } else {
            editedApprovalText = "";
            approvalCard.setVisibility(View.GONE);
        }

        if (runtime != null) {
            voiceButton.setText("結束語音");
        } else if (session.status() == CommunicationSession.Status.REPLY_RECEIVED) {
            voiceButton.setText("讓 Mate 繼續");
        } else if (session.status() == CommunicationSession.Status.NEEDS_USER_INPUT) {
            voiceButton.setText("回答 Mate");
        } else if (session.status() == CommunicationSession.Status.COMPLETED) {
            voiceButton.setText("交代新任務");
        } else {
            voiceButton.setText("補充給 Mate");
        }
    }

    private void renderTimelines(CommunicationSession session, String person) {
        boolean followExternal = isNearBottom(externalScroll);
        boolean followPrivate = isNearBottom(privateScroll);
        externalTimeline.removeAllViews();
        privateTimeline.removeAllViews();

        int externalCount = 0;
        int privateCount = 0;
        for (Message message : session.messages()) {
            boolean external = message.sender == Message.Sender.OTHER_PERSON
                    || (message.sender == Message.Sender.MATE && !"USER".equals(message.recipient));
            if (external) {
                boolean mate = message.sender == Message.Sender.MATE;
                String label = mate ? "Mate → " + person : person + " → Mate";
                String state = externalStatus(message.status());
                if (!state.isEmpty()) label += " · " + state;
                addBubble(externalTimeline, label, message.content(), mate, false);
                externalCount++;
            } else if (message.sender == Message.Sender.USER || message.sender == Message.Sender.MATE) {
                boolean user = message.sender == Message.Sender.USER;
                addBubble(privateTimeline, user ? "你 → Mate" : "Mate → 你", message.content(), user, true);
                privateCount++;
            }
        }

        if (externalCount == 0) {
            addPlaceholder(externalTimeline, "Mate 準備好後，對外送出的內容與對方每一次回覆都會完整顯示在這裡。");
        }
        if (privateCount == 0) {
            addPlaceholder(privateTimeline, "這裡只放你交代給 Mate 的內容，不會原文轉給對方。");
        }
        if (followExternal) scrollToBottom(externalScroll);
        if (followPrivate) scrollToBottom(privateScroll);
    }

    private void addBubble(LinearLayout container, String label, String content, boolean right, boolean privateBubble) {
        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(dp(12), dp(9), dp(12), dp(10));
        int color;
        if (privateBubble) color = right ? privateUserSurface : surface2;
        else color = right ? accentSurface : inboundSurface;
        bubble.setBackground(roundRect(color, 14));

        TextView meta = new TextView(this);
        meta.setText(label);
        meta.setTextSize(10);
        meta.setTextColor(muted);
        meta.setTypeface(Typeface.DEFAULT_BOLD);
        bubble.addView(meta);

        TextView body = new TextView(this);
        body.setText(content == null ? "" : content);
        body.setTextSize(13);
        body.setTextColor(text);
        body.setLineSpacing(0, 1.16f);
        body.setPadding(0, dp(4), 0, 0);
        bubble.addView(body);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = right ? Gravity.END : Gravity.START;
        lp.setMargins(right ? dp(44) : 0, dp(4), right ? 0 : dp(44), dp(5));
        bubble.setMinimumWidth(dp(110));
        bubble.setLayoutParams(lp);
        container.addView(bubble);
    }

    private void addPlaceholder(LinearLayout container, String value) {
        TextView placeholder = new TextView(this);
        placeholder.setText(value);
        placeholder.setTextColor(muted);
        placeholder.setTextSize(12);
        placeholder.setLineSpacing(0, 1.15f);
        placeholder.setPadding(dp(8), dp(8), dp(8), dp(8));
        container.addView(placeholder);
    }

    private String externalStatus(Message.Status status) {
        if (status == null) return "";
        switch (status) {
            case DRAFT: return "草稿";
            case PENDING_APPROVAL: return "未送出";
            case SENDING: return "傳送中";
            case SENT: return "已送出";
            case DELIVERED: return "已送出";
            case FAILED: return "失敗";
            case CANCELLED: return "已取消";
            default: return "";
        }
    }

    private boolean isNearBottom(ScrollView scroll) {
        if (scroll == null || scroll.getChildCount() == 0) return true;
        View child = scroll.getChildAt(0);
        return scroll.getScrollY() + scroll.getHeight() >= child.getHeight() - dp(56);
    }

    private void scrollToBottom(final ScrollView scroll) {
        if (scroll == null) return;
        scroll.post(new Runnable() {
            @Override public void run() { scroll.fullScroll(View.FOCUS_DOWN); }
        });
    }

    private int statusColor(String value) {
        if (value == null) return muted;
        if (value.contains("approval") || value.contains("input") || value.contains("Connecting")) return amber;
        if (value.contains("Sending") || value.contains("Listening") || value.contains("reply")
                || value.contains("Reply") || value.contains("Completed") || value.contains("Waiting for reply")) return green;
        if (value.contains("Error") || value.contains("failed")) return red;
        return muted;
    }

    private String friendlyStatus(String value) {
        if (value == null) return "";
        if (value.contains("Waiting for approval")) return "等你確認";
        if (value.contains("Needs your input")) return "需要你決定";
        if (value.contains("Waiting for reply")) return "等待對方回覆";
        if (value.contains("Reply received")) return "對方已回覆";
        if (value.contains("Sending")) return "正在傳送";
        if (value.contains("Listening")) return "正在聽你說";
        if (value.contains("Mate is speaking")) return "Mate 正在回覆你";
        if (value.contains("Connecting")) return "連線中";
        if (value.contains("Completed")) return "已完成";
        if (value.contains("Paused") || value.contains("Stopped")) return "已暫停";
        if (value.contains("Thinking")) return "Mate 正在處理";
        if (value.contains("Error")) return "發生錯誤";
        return value;
    }

    private void status(String value, int color) {
        if (statusText == null) return;
        statusText.setText(friendlyStatus(value));
        statusText.setTextColor(color);
        int bgColor = color == amber ? Color.rgb(64, 48, 20)
                : color == green ? Color.rgb(18, 56, 48)
                : color == red ? Color.rgb(70, 30, 38)
                : surface2;
        statusText.setBackground(roundRect(bgColor, 12));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED && pendingStartAfterPermission) {
                pendingStartAfterPermission = false;
                startRuntime(AppConfig.getApiKey(this));
            } else {
                pendingStartAfterPermission = false;
                Toast.makeText(this, "Crew Mate 需要麥克風權限才能使用語音。", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(flushInputTurnRunnable);
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
        view.setLineSpacing(0, 1.2f);
        view.setPadding(dp(16), dp(14), dp(16), dp(14));
        return view;
    }

    private Button actionButton(String label, int color) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(11);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setBackground(roundRect(color, 11));
        return button;
    }

    private LinearLayout.LayoutParams cardLp(int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
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
