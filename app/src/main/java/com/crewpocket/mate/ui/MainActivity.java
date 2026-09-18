package com.crewpocket.mate.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.crewpocket.mate.agent.CrewMateRuntime;
import com.crewpocket.mate.config.AppConfig;
import com.crewpocket.mate.model.CommunicationSession;
import com.crewpocket.mate.model.LiveCallState;
import com.crewpocket.mate.model.Message;
import com.crewpocket.mate.model.SpeechAudience;
import com.crewpocket.mate.storage.SessionStore;
import com.crewpocket.mate.voice.GeminiLiveModelSession;
import com.crewpocket.mate.voice.TurnTextAccumulator;

import java.util.List;
import java.util.Locale;

/** Task-first UI with an explicit speech audience boundary. */
public class MainActivity extends Activity {
    private static final int REQUEST_AUDIO = 701;
    private static final long INPUT_TRANSCRIPT_SETTLE_MS = 900L;

    private static final String[] LANGUAGE_CODES = new String[]{
            "AUTO", "zh-TW", "en-US", "th-TH", "ja-JP", "ko-KR", "vi-VN",
            "zh-CN", "id-ID", "ms-MY", "es-ES", "fr-FR", "de-DE"
    };
    private static final String[] LANGUAGE_LABELS = new String[]{
            "自動辨識", "繁體中文", "English", "ไทย", "日本語", "한국어", "Tiếng Việt",
            "简体中文", "Bahasa Indonesia", "Bahasa Melayu", "Español", "Français", "Deutsch"
    };
    private static final String[] LANGUAGE_SHORT_LABELS = new String[]{
            "自動", "繁中", "EN", "ไทย", "日本語", "한국어", "VI",
            "简中", "ID", "MY", "ES", "FR", "DE"
    };

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
    private final int direct = Color.rgb(249, 115, 22);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TurnTextAccumulator inputTurn = new TurnTextAccumulator();
    private final Runnable flushInputTurnRunnable = new Runnable() {
        @Override public void run() { flushInputTurn(); }
    };

    private Button primaryButton;
    private Button directButton;
    private Button settingsButton;
    private LinearLayout callBar;
    private TextView callStateText;
    private Button languageButton;
    private Button callToggleButton;
    private TextView statusText;
    private TextView taskText;
    private LinearLayout taskComposerCard;
    private TextView composerLabel;
    private EditText taskInput;
    private Button taskInputButton;
    private Button taskVoiceButton;
    private LinearLayout modeActions;
    private LinearLayout utilities;
    private Button newTaskButton;
    private Button historyButton;
    private LinearLayout audienceCard;
    private TextView audienceTitle;
    private TextView audienceDetail;
    private TextView externalSectionTitle;
    private TextView privateSectionTitle;
    private LinearLayout externalTimeline;
    private LinearLayout privateTimeline;
    private ScrollView externalScroll;
    private ScrollView privateScroll;
    private CrewMateRuntime runtime;
    private GeminiLiveModelSession modelSession;
    private SessionStore sessionStore;
    private CommunicationSession viewedSession;
    private LiveCallState liveCallState = LiveCallState.OFF;
    private SpeechAudience speechAudience = SpeechAudience.IDLE;
    private SpeechAudience pendingInputAudience = SpeechAudience.IDLE;
    private SpeechAudience privateReturnAudience = SpeechAudience.MATE_HANDLING;
    private SpeechAudience pendingAudienceAfterPermission = SpeechAudience.PRIVATE_TO_MATE;
    private boolean pendingStartAfterPermission;
    private boolean pendingHandoffAfterPermission;
    private String pendingTypedBrief = "";
    private String selectedUserLanguage = "AUTO";
    private String selectedOtherLanguage = "AUTO";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        sessionStore = new SessionStore(this);
        selectedUserLanguage = defaultUserLanguage();
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
        viewedSession = sessionStore.loadLatest();
        if (viewedSession != null) {
            selectedUserLanguage = viewedSession.userLanguage();
            selectedOtherLanguage = viewedSession.otherPersonLanguage();
        }
        speechAudience = passiveAudience(viewedSession);
        renderLanguageControl();
        renderSession(viewedSession);
        if (viewedSession == null) {
            status("Ready", muted);
        } else if (viewedSession.userDirectControl()) {
            status("User direct", direct);
        } else {
            String value = CrewMateRuntime.displayStatus(viewedSession.status());
            status(value, statusColor(value));
        }
    }

    private SpeechAudience passiveAudience(CommunicationSession session) {
        if (session == null) return SpeechAudience.IDLE;
        if (session.userDirectControl()) return SpeechAudience.USER_DIRECT;
        if (session.status() == CommunicationSession.Status.COMPLETED) return SpeechAudience.IDLE;
        return SpeechAudience.MATE_HANDLING;
    }

    private View buildUi() {
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        final int baseLeft = dp(18);
        final int baseTop = dp(16);
        final int baseRight = dp(18);
        final int baseBottom = dp(16);
        root.setPadding(baseLeft, baseTop, baseRight, baseBottom);
        root.setBackgroundColor(bg);
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int left;
                int top;
                int right;
                int bottom;
                if (Build.VERSION.SDK_INT >= 30) {
                    Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                    left = bars.left;
                    top = bars.top;
                    right = bars.right;
                    bottom = bars.bottom;
                } else {
                    left = insets.getSystemWindowInsetLeft();
                    top = insets.getSystemWindowInsetTop();
                    right = insets.getSystemWindowInsetRight();
                    bottom = insets.getSystemWindowInsetBottom();
                }
                v.setPadding(
                        baseLeft + left,
                        baseTop + top,
                        baseRight + right,
                        baseBottom + bottom);
                return insets;
            }
        });

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
        subtitle.setText("把想說的交給 Mate。");
        subtitle.setTextSize(12);
        subtitle.setTextColor(muted);
        subtitle.setPadding(0, dp(2), 0, 0);
        titleBox.addView(subtitle);
        top.addView(titleBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        newTaskButton = actionButton("＋ 新任務", surface2);
        newTaskButton.setTextSize(11);
        newTaskButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestNewTask(); }
        });
        top.addView(newTaskButton, new LinearLayout.LayoutParams(dp(86), dp(38)));

        settingsButton = actionButton("設定", surface2);
        settingsButton.setTextSize(11);
        settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSettings(); }
        });
        LinearLayout.LayoutParams settingsLp = new LinearLayout.LayoutParams(dp(64), dp(38));
        settingsLp.setMargins(dp(6), 0, 0, 0);
        top.addView(settingsButton, settingsLp);
        root.addView(top);

        callBar = new LinearLayout(this);
        callBar.setOrientation(LinearLayout.HORIZONTAL);
        callBar.setGravity(Gravity.CENTER_VERTICAL);
        callBar.setPadding(dp(12), dp(8), dp(8), dp(8));
        callBar.setBackground(roundRect(surface, 14));

        callStateText = new TextView(this);
        callStateText.setTextSize(12);
        callStateText.setTypeface(Typeface.DEFAULT_BOLD);
        callBar.addView(callStateText,
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        languageButton = actionButton("🌐 自動 → 自動", surface2);
        languageButton.setTextSize(10);
        languageButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showLanguagePicker(); }
        });
        LinearLayout.LayoutParams languageLp = new LinearLayout.LayoutParams(dp(104), dp(38));
        languageLp.setMargins(dp(6), 0, dp(6), 0);
        callBar.addView(languageButton, languageLp);

        callToggleButton = actionButton("開啟", surface2);
        callToggleButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { handleLiveCallToggle(); }
        });
        callBar.addView(callToggleButton, new LinearLayout.LayoutParams(dp(82), dp(38)));

        LinearLayout.LayoutParams callLp = cardLp(dp(10));
        callLp.setMargins(0, dp(12), 0, dp(10));
        root.addView(callBar, callLp);
        renderLanguageControl();
        renderLiveCallControl();

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
        root.addView(taskText, cardLp(dp(10)));

        taskComposerCard = new LinearLayout(this);
        taskComposerCard.setOrientation(LinearLayout.VERTICAL);
        taskComposerCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        taskComposerCard.setBackground(roundRect(surface, 18));
        composerLabel = new TextView(this);
        composerLabel.setText("先告訴 Mate 你想做什麼");
        composerLabel.setTextColor(text);
        composerLabel.setTextSize(16);
        composerLabel.setTypeface(Typeface.DEFAULT_BOLD);
        taskComposerCard.addView(composerLabel);

        taskVoiceButton = actionButton("🎙  口頭交代", accent);
        taskVoiceButton.setTextSize(14);
        taskVoiceButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { handlePrimaryAction(); }
        });
        LinearLayout.LayoutParams voiceLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        voiceLp.setMargins(0, dp(12), 0, dp(12));
        taskComposerCard.addView(taskVoiceButton, voiceLp);

        taskInput = new EditText(this);
        taskInput.setHint("或直接輸入，例如：幫我問櫃台能不能延後退房，超過 500 泰銖先問我");
        taskInput.setTextColor(text);
        taskInput.setHintTextColor(muted);
        taskInput.setTextSize(13);
        taskInput.setSingleLine(false);
        taskInput.setMinLines(2);
        taskInput.setMaxLines(4);
        taskInput.setPadding(dp(10), dp(8), dp(10), dp(8));
        taskInput.setBackground(roundRect(surface2, 12));
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputLp.setMargins(0, dp(10), 0, dp(8));
        taskComposerCard.addView(taskInput, inputLp);
        taskInputButton = actionButton("送出文字", accentSurface);
        taskInputButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { submitTypedBrief(); }
        });
        taskComposerCard.addView(taskInputButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        root.addView(taskComposerCard, cardLp(dp(10)));

        audienceCard = new LinearLayout(this);
        audienceCard.setOrientation(LinearLayout.VERTICAL);
        audienceCard.setPadding(dp(15), dp(12), dp(15), dp(12));
        audienceTitle = new TextView(this);
        audienceTitle.setTextSize(14);
        audienceTitle.setTypeface(Typeface.DEFAULT_BOLD);
        audienceCard.addView(audienceTitle);
        audienceDetail = new TextView(this);
        audienceDetail.setTextSize(12);
        audienceDetail.setLineSpacing(0, 1.15f);
        audienceDetail.setPadding(0, dp(5), 0, 0);
        audienceCard.addView(audienceDetail);
        root.addView(audienceCard, cardLp(dp(10)));

        externalSectionTitle = sectionTitle("對外紀錄");
        root.addView(externalSectionTitle);
        externalTimeline = new LinearLayout(this);
        externalTimeline.setOrientation(LinearLayout.VERTICAL);
        externalTimeline.setPadding(dp(10), dp(10), dp(10), dp(10));
        externalScroll = new ScrollView(this);
        externalScroll.setFillViewport(true);
        externalScroll.addView(externalTimeline);
        externalScroll.setBackground(roundRect(surface, 18));
        LinearLayout.LayoutParams externalLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.2f);
        externalLp.setMargins(0, dp(6), 0, dp(10));
        root.addView(externalScroll, externalLp);

        privateSectionTitle = sectionTitle("🔒 私人 · 你 ↔ Mate");
        root.addView(privateSectionTitle);
        privateTimeline = new LinearLayout(this);
        privateTimeline.setOrientation(LinearLayout.VERTICAL);
        privateTimeline.setPadding(dp(10), dp(8), dp(10), dp(8));
        privateScroll = new ScrollView(this);
        privateScroll.setFillViewport(true);
        privateScroll.addView(privateTimeline);
        privateScroll.setBackground(roundRect(surface, 18));
        LinearLayout.LayoutParams privateLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.45f);
        privateLp.setMargins(0, dp(6), 0, dp(10));
        root.addView(privateScroll, privateLp);

        modeActions = new LinearLayout(this);
        modeActions.setOrientation(LinearLayout.HORIZONTAL);
        primaryButton = actionButton("🔒 交代給 Mate", accent);
        primaryButton.setTextSize(13);
        primaryButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { handlePrimaryAction(); }
        });
        modeActions.addView(primaryButton, new LinearLayout.LayoutParams(0, dp(52), 1.45f));
        directButton = actionButton("我要自己說", direct);
        directButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { handleSecondaryAction(); }
        });
        LinearLayout.LayoutParams directLp = new LinearLayout.LayoutParams(0, dp(52), 1f);
        directLp.setMargins(dp(8), 0, 0, 0);
        modeActions.addView(directButton, directLp);
        root.addView(modeActions);

        utilities = new LinearLayout(this);
        utilities.setOrientation(LinearLayout.HORIZONTAL);
        historyButton = actionButton("查看紀錄", surface2);
        historyButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showHistory(); }
        });
        utilities.addView(historyButton,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
        LinearLayout.LayoutParams utilitiesLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        utilitiesLp.setMargins(0, dp(8), 0, 0);
        root.addView(utilities, utilitiesLp);
        return root;
    }

    private void handlePrimaryAction() {
        if (speechAudience == SpeechAudience.USER_DIRECT) {
            handBackToMate();
            return;
        }
        if (viewedSession != null && viewedSession.status() == CommunicationSession.Status.COMPLETED) {
            startFreshTask();
            return;
        }
        if (speechAudience == SpeechAudience.EXTERNAL_WITH_MATE) {
            enterPrivateSupplement();
            return;
        }
        if (speechAudience == SpeechAudience.PRIVATE_TO_MATE && runtime != null) {
            flushInputTurn();
            if (privateReturnAudience == SpeechAudience.EXTERNAL_WITH_MATE) {
                applyAudience(SpeechAudience.EXTERNAL_WITH_MATE);
                Toast.makeText(this, "已加入 Mate context", Toast.LENGTH_SHORT).show();
                status("External live", green);
            } else {
                applyAudience(SpeechAudience.MATE_HANDLING);
                status("Mate handling", green);
            }
            return;
        }
        if (isInPersonMode() && taskReady(viewedSession)) {
            if (runtime != null) {
                handToOtherPerson();
            } else {
                ensureRuntime(SpeechAudience.EXTERNAL_WITH_MATE);
            }
            return;
        }
        if (runtime != null) {
            enterPrivateSupplement();
            return;
        }
        privateReturnAudience = SpeechAudience.MATE_HANDLING;
        ensureRuntime(SpeechAudience.PRIVATE_TO_MATE);
    }

    private void showLanguagePicker() {
        String[] choices = new String[]{
                "我的語言：" + languageLabel(selectedUserLanguage),
                "對方語言：" + languageLabel(selectedOtherLanguage)
        };
        new AlertDialog.Builder(this)
                .setTitle("對話語言")
                .setItems(choices, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        showLanguageChoices(which == 0);
                    }
                })
                .setNegativeButton("關閉", null)
                .show();
    }

    private void showLanguageChoices(final boolean userSide) {
        final String current = userSide ? selectedUserLanguage : selectedOtherLanguage;
        int checked = languageIndex(current);
        new AlertDialog.Builder(this)
                .setTitle(userSide ? "我的語言" : "對方語言")
                .setSingleChoiceItems(LANGUAGE_LABELS, checked, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        String code = LANGUAGE_CODES[Math.max(0, Math.min(which, LANGUAGE_CODES.length - 1))];
                        if (userSide) {
                            selectedUserLanguage = code;
                        } else {
                            selectedOtherLanguage = code;
                        }
                        applySelectedLanguages();
                        dialog.dismiss();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void applySelectedLanguages() {
        if (viewedSession != null) {
            viewedSession.setLanguages(selectedUserLanguage, selectedOtherLanguage);
            sessionStore.save(viewedSession);
        }
        if (modelSession != null) {
            modelSession.setConversationLanguages(selectedUserLanguage, selectedOtherLanguage);
        }
        renderLanguageControl();
        renderSession(viewedSession);
    }

    private void renderLanguageControl() {
        if (languageButton == null) return;
        languageButton.setText("🌐 " + languageShortLabel(selectedUserLanguage)
                + " → " + languageShortLabel(selectedOtherLanguage));
    }

    private static int languageIndex(String code) {
        String value = code == null ? "AUTO" : code;
        for (int i = 0; i < LANGUAGE_CODES.length; i++) {
            if (LANGUAGE_CODES[i].equalsIgnoreCase(value)) return i;
        }
        return 0;
    }

    private static String languageLabel(String code) {
        return LANGUAGE_LABELS[languageIndex(code)];
    }

    private static String languageShortLabel(String code) {
        return LANGUAGE_SHORT_LABELS[languageIndex(code)];
    }

    private static String defaultUserLanguage() {
        Locale locale = Locale.getDefault();
        String language = locale == null ? "" : locale.getLanguage();
        String country = locale == null ? "" : locale.getCountry();
        if ("zh".equalsIgnoreCase(language)) {
            return "CN".equalsIgnoreCase(country) ? "zh-CN" : "zh-TW";
        }
        if ("th".equalsIgnoreCase(language)) return "th-TH";
        if ("ja".equalsIgnoreCase(language)) return "ja-JP";
        if ("ko".equalsIgnoreCase(language)) return "ko-KR";
        if ("vi".equalsIgnoreCase(language)) return "vi-VN";
        if ("id".equalsIgnoreCase(language)) return "id-ID";
        if ("ms".equalsIgnoreCase(language)) return "ms-MY";
        if ("es".equalsIgnoreCase(language)) return "es-ES";
        if ("fr".equalsIgnoreCase(language)) return "fr-FR";
        if ("de".equalsIgnoreCase(language)) return "de-DE";
        if ("en".equalsIgnoreCase(language)) return "en-US";
        return "AUTO";
    }

    private void handleLiveCallToggle() {
        if (liveCallState == LiveCallState.ACTIVE || liveCallState == LiveCallState.CONNECTING) {
            if (runtime != null) {
                stopRuntime();
            } else {
                setLiveCallState(LiveCallState.OFF);
            }
            return;
        }

        SpeechAudience desiredAudience = preferredCallAudience();
        if (runtime != null) stopRuntime();
        ensureRuntime(desiredAudience);
    }

    private SpeechAudience preferredCallAudience() {
        if (speechAudience == SpeechAudience.USER_DIRECT) return SpeechAudience.USER_DIRECT;
        if (speechAudience == SpeechAudience.PRIVATE_TO_MATE) return SpeechAudience.PRIVATE_TO_MATE;
        if (speechAudience == SpeechAudience.EXTERNAL_WITH_MATE) return SpeechAudience.EXTERNAL_WITH_MATE;
        return taskReady(viewedSession)
                ? SpeechAudience.MATE_HANDLING
                : SpeechAudience.PRIVATE_TO_MATE;
    }

    private void setLiveCallState(LiveCallState state) {
        liveCallState = state == null ? LiveCallState.OFF : state;
        renderLiveCallControl();
    }

    private void renderLiveCallControl() {
        if (callStateText == null || callToggleButton == null || callBar == null) return;
        switch (liveCallState) {
            case CONNECTING:
                callStateText.setText("AI 通話連線中…");
                callStateText.setTextColor(amber);
                callToggleButton.setText("取消");
                callToggleButton.setBackground(roundRect(surface2, 11));
                break;
            case ACTIVE:
                callStateText.setText("● AI 通話中");
                callStateText.setTextColor(green);
                callToggleButton.setText("結束");
                callToggleButton.setBackground(roundRect(Color.rgb(127, 29, 29), 11));
                break;
            case ERROR:
                callStateText.setText("AI 通話連線失敗");
                callStateText.setTextColor(red);
                callToggleButton.setText("重試");
                callToggleButton.setBackground(roundRect(accent, 11));
                break;
            case OFF:
            default:
                callStateText.setText("AI 通話已關閉");
                callStateText.setTextColor(muted);
                callToggleButton.setText("開啟");
                callToggleButton.setBackground(roundRect(surface2, 11));
                break;
        }
    }

    private void submitTypedBrief() {
        String value = taskInput == null ? "" : taskInput.getText().toString().trim();
        if (value.isEmpty()) return;
        if (taskInput != null) taskInput.setText("");
        if (runtime == null) {
            pendingTypedBrief = value;
            ensureRuntime(SpeechAudience.MATE_HANDLING);
            return;
        }

        runtime.submitPrivateText(value);

        // Typed input is already private; do not turn the microphone on just to submit text.
        if (speechAudience == SpeechAudience.PRIVATE_TO_MATE
                && privateReturnAudience == SpeechAudience.EXTERNAL_WITH_MATE) {
            applyAudience(SpeechAudience.EXTERNAL_WITH_MATE);
            Toast.makeText(this, "已加入 Mate context", Toast.LENGTH_SHORT).show();
            status("External live", green);
        } else if (speechAudience == SpeechAudience.PRIVATE_TO_MATE) {
            applyAudience(SpeechAudience.MATE_HANDLING);
            status("Mate handling", green);
        } else {
            status("Mate handling", green);
        }
    }

    private boolean taskReady(CommunicationSession session) {
        return session != null && !session.targetPerson().isEmpty() && !session.goal().isEmpty();
    }

    private void handleSecondaryAction() {
        if (speechAudience == SpeechAudience.MATE_HANDLING && isInPersonMode()) {
            enterPrivateSupplement();
            return;
        }
        enterUserDirectMode();
    }

    private void enterPrivateSupplement() {
        privateReturnAudience = speechAudience == SpeechAudience.EXTERNAL_WITH_MATE
                || (viewedSession != null
                && viewedSession.status() == CommunicationSession.Status.NEEDS_USER_INPUT
                && !viewedSession.targetPerson().isEmpty())
                ? SpeechAudience.EXTERNAL_WITH_MATE
                : SpeechAudience.MATE_HANDLING;
        if (runtime == null) {
            ensureRuntime(SpeechAudience.PRIVATE_TO_MATE);
            return;
        }
        applyAudience(SpeechAudience.PRIVATE_TO_MATE);
        status("Private context", accent);
    }

    private void handToOtherPerson() {
        if (runtime == null || viewedSession == null || !taskReady(viewedSession)) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingHandoffAfterPermission = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO);
            return;
        }
        viewedSession.setUserDirectControl(false);
        sessionStore.save(viewedSession);
        privateReturnAudience = SpeechAudience.EXTERNAL_WITH_MATE;
        applyAudience(SpeechAudience.EXTERNAL_WITH_MATE);
        status("External live", green);
    }

    private void enterUserDirectMode() {
        if (viewedSession == null || viewedSession.targetPerson().isEmpty()
                || viewedSession.status() == CommunicationSession.Status.COMPLETED) return;
        flushInputTurn();
        handler.removeCallbacks(flushInputTurnRunnable);
        inputTurn.clear();
        viewedSession.setUserDirectControl(true);
        sessionStore.save(viewedSession);
        applyAudience(SpeechAudience.USER_DIRECT);
        if (runtime != null) runtime.interrupt();
        status("User direct", direct);
        renderSession(viewedSession);
    }

    private void handBackToMate() {
        if (viewedSession == null) return;
        viewedSession.setUserDirectControl(false);
        sessionStore.save(viewedSession);
        if (runtime != null) {
            runtime.releaseUserDirectControl();
            applyAudience(SpeechAudience.EXTERNAL_WITH_MATE);
            status("External live", green);
        } else {
            ensureRuntime(SpeechAudience.EXTERNAL_WITH_MATE);
        }
    }

    private void applyAudience(SpeechAudience audience) {
        SpeechAudience next = audience == null ? SpeechAudience.IDLE : audience;
        if (speechAudience != next && speechAudience.routesMicrophoneToMate()) flushInputTurn();
        speechAudience = next;
        if (!speechAudience.routesMicrophoneToMate()) {
            handler.removeCallbacks(flushInputTurnRunnable);
            inputTurn.clear();
            pendingInputAudience = SpeechAudience.IDLE;
        }
        if (runtime != null) runtime.setSpeechAudience(speechAudience);
        if (modelSession != null) modelSession.setSpeechAudience(speechAudience);
        renderAudience(viewedSession);
        renderControls(viewedSession);
    }

    private void ensureRuntime(SpeechAudience initialAudience) {
        String key = AppConfig.getApiKey(this);
        if (key.isEmpty()) {
            Toast.makeText(this, "先到設定填入 Gemini API key。", Toast.LENGTH_SHORT).show();
            showSettings();
            return;
        }
        if (initialAudience.routesMicrophoneToMate()
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingStartAfterPermission = true;
            pendingAudienceAfterPermission = initialAudience;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO);
            return;
        }
        startRuntime(key, initialAudience);
    }

    private boolean isInPersonMode() {
        return true;
    }

    private void startRuntime(String key, final SpeechAudience initialAudience) {
        setLiveCallState(LiveCallState.CONNECTING);
        inputTurn.clear();
        pendingInputAudience = SpeechAudience.IDLE;
        handler.removeCallbacks(flushInputTurnRunnable);
        final CommunicationSession liveSession;
        final boolean resumeExisting = shouldResume(viewedSession);
        if (resumeExisting) {
            liveSession = viewedSession;
        } else {
            liveSession = new CommunicationSession();
            liveSession.setLanguages(selectedUserLanguage, selectedOtherLanguage);
            viewedSession = liveSession;
            sessionStore.save(liveSession);
        }

        liveSession.setLanguages(selectedUserLanguage, selectedOtherLanguage);
        speechAudience = initialAudience == null ? SpeechAudience.MATE_HANDLING : initialAudience;

        modelSession = new GeminiLiveModelSession(this, key, AppConfig.getVoice(this), new GeminiLiveModelSession.UiListener() {
            @Override public void onStatus(final String value) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if ("Listening".equals(value) || "Ready".equals(value)) {
                            setLiveCallState(LiveCallState.ACTIVE);
                        } else if (value != null && value.contains("Connecting")) {
                            setLiveCallState(LiveCallState.CONNECTING);
                        } else if ("Stopped".equals(value)) {
                            setLiveCallState(LiveCallState.OFF);
                        }
                        if (speechAudience == SpeechAudience.PRIVATE_TO_MATE && "Listening".equals(value)) {
                            status("Listening", accent);
                        }
                        if (("Listening".equals(value) || "Ready".equals(value))
                                && !pendingTypedBrief.isEmpty() && runtime != null) {
                            String pending = pendingTypedBrief;
                            pendingTypedBrief = "";
                            runtime.submitPrivateText(pending);
                            applyAudience(SpeechAudience.MATE_HANDLING);
                            status("Mate handling", green);
                        }
                    }
                });
            }

            @Override public void onInputTranscript(final String value, final SpeechAudience audience) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (audience != SpeechAudience.PRIVATE_TO_MATE
                                && audience != SpeechAudience.EXTERNAL_WITH_MATE) return;
                        if (pendingInputAudience != SpeechAudience.IDLE && pendingInputAudience != audience) {
                            flushInputTurn();
                        }
                        pendingInputAudience = audience;
                        inputTurn.append(value);
                        handler.removeCallbacks(flushInputTurnRunnable);
                        handler.postDelayed(flushInputTurnRunnable, INPUT_TRANSCRIPT_SETTLE_MS);
                    }
                });
            }

            @Override public void onSpeakingChanged(final boolean speaking) {
                if (!speaking) return;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (speechAudience == SpeechAudience.PRIVATE_TO_MATE
                                || speechAudience == SpeechAudience.EXTERNAL_WITH_MATE) {
                            flushInputTurn();
                            status(speechAudience == SpeechAudience.EXTERNAL_WITH_MATE
                                    ? "Mate speaking externally"
                                    : "Mate is speaking…", accent);
                        }
                    }
                });
            }

            @Override public void onError(final String message) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        setLiveCallState(LiveCallState.ERROR);
                        status(message, red);
                    }
                });
            }
        });
        modelSession.setConversationLanguages(selectedUserLanguage, selectedOtherLanguage);
        modelSession.setSpeechAudience(speechAudience);

        runtime = new CrewMateRuntime(liveSession, modelSession, new CrewMateRuntime.Listener() {
            @Override public void onSessionChanged(final CommunicationSession session) {
                sessionStore.save(session);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        viewedSession = session;
                        if (session.userDirectControl() && speechAudience != SpeechAudience.USER_DIRECT) {
                            applyAudience(SpeechAudience.USER_DIRECT);
                        } else if (session.status() == CommunicationSession.Status.NEEDS_USER_INPUT
                                && speechAudience == SpeechAudience.EXTERNAL_WITH_MATE) {
                            privateReturnAudience = SpeechAudience.EXTERNAL_WITH_MATE;
                            applyAudience(SpeechAudience.MATE_HANDLING);
                        }
                        renderSession(session);
                    }
                });
            }

            @Override public void onRuntimeStatus(final String value) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (speechAudience == SpeechAudience.USER_DIRECT) return;
                        if (value != null && !"Thinking".equals(value)) flushInputTurn();
                        status(value, statusColor(value));
                    }
                });
            }
        });
        runtime.setSpeechAudience(speechAudience);

        status("Connecting…", amber);
        renderSession(liveSession);
        runtime.start();
        applyAudience(speechAudience);

    }

    private boolean shouldResume(CommunicationSession session) {
        return session != null && session.status() != CommunicationSession.Status.COMPLETED;
    }

    private void flushInputTurn() {
        handler.removeCallbacks(flushInputTurnRunnable);
        SpeechAudience audience = pendingInputAudience;
        pendingInputAudience = SpeechAudience.IDLE;
        String completed = inputTurn.take();
        if (completed.isEmpty()) return;
        CrewMateRuntime target = runtime;
        if (target == null) return;
        if (audience == SpeechAudience.PRIVATE_TO_MATE) {
            target.recordUserTranscript(completed);
        } else if (audience == SpeechAudience.EXTERNAL_WITH_MATE) {
            target.recordExternalSpeechTranscript(completed);
        }
    }

    private void stopRuntime() {
        if (speechAudience.routesMicrophoneToMate()) flushInputTurn();
        CrewMateRuntime target = runtime;
        CommunicationSession session = target == null ? viewedSession : target.session();
        if (session != null) sessionStore.save(session);
        if (target != null) target.close();
        runtime = null;
        modelSession = null;
        setLiveCallState(LiveCallState.OFF);
        if (session != null) {
            viewedSession = session;
            sessionStore.save(session);
        }
        if (session != null && session.userDirectControl()) {
            speechAudience = SpeechAudience.USER_DIRECT;
            status("User direct", direct);
        } else {
            speechAudience = passiveAudience(session);
            status(session == null ? "Ready" : CrewMateRuntime.displayStatus(session.status()), muted);
        }
        renderSession(viewedSession);
    }

    private void requestNewTask() {
        if (viewedSession == null && runtime == null) {
            startFreshTask();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("開始新任務？")
                .setMessage("目前任務會保留在紀錄中，AI 通話會結束。")
                .setPositiveButton("開始新任務", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        startFreshTask();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void startFreshTask() {
        if (runtime != null) stopRuntime();
        viewedSession = null;
        speechAudience = SpeechAudience.IDLE;
        privateReturnAudience = SpeechAudience.MATE_HANDLING;
        selectedUserLanguage = defaultUserLanguage();
        selectedOtherLanguage = "AUTO";
        pendingTypedBrief = "";
        if (taskInput != null) taskInput.setText("");
        setLiveCallState(LiveCallState.OFF);
        renderLanguageControl();
        renderSession(null);
        status("Ready", muted);
    }

    private void showHistory() {
        if (runtime != null) {
            Toast.makeText(this, "請先結束目前的即時對話再查看紀錄。", Toast.LENGTH_SHORT).show();
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
                        selectedUserLanguage = viewedSession.userLanguage();
                        selectedOtherLanguage = viewedSession.otherPersonLanguage();
                        speechAudience = passiveAudience(viewedSession);
                        renderLanguageControl();
                        renderSession(viewedSession);
                    }
                })
                .setNegativeButton("關閉", null)
                .show();
    }

    private void showSettings() {
        if (runtime != null) {
            Toast.makeText(this, "請先結束目前的即時 session 再修改設定。", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(4), dp(22), 0);
        final EditText api = dialogInput("Gemini API key", true);
        api.setText(AppConfig.getApiKey(this));
        box.addView(api, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));

        TextView note = new TextView(this);
        note.setText("現場語音模式：把手機交給對方後，Mate 只會根據麥克風實際聽到的內容回應。");
        note.setTextColor(Color.DKGRAY);
        note.setTextSize(12);
        note.setPadding(0, dp(10), 0, 0);
        box.addView(note);

        new AlertDialog.Builder(this)
                .setTitle("Crew Mate 設定")
                .setView(box)
                .setPositiveButton("儲存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        AppConfig.setApiKey(MainActivity.this, api.getText().toString());
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

    private void renderSession(CommunicationSession session) {
        boolean active = session != null;
        boolean inPerson = isInPersonMode();
        boolean privateEditing = speechAudience == SpeechAudience.PRIVATE_TO_MATE;
        boolean taskIsReady = taskReady(session);
        boolean publicConversation = active && (speechAudience == SpeechAudience.EXTERNAL_WITH_MATE
                || speechAudience == SpeechAudience.USER_DIRECT || hasExternalMessages(session));

        if (inPerson) {
            // Physical handoff has three product screens: setup -> confirm -> conversation.
            statusText.setVisibility(active && (publicConversation
                    || session.status() == CommunicationSession.Status.NEEDS_USER_INPUT)
                    ? View.VISIBLE : View.GONE);
            taskComposerCard.setVisibility((!active || !taskIsReady || privateEditing)
                    && speechAudience != SpeechAudience.USER_DIRECT ? View.VISIBLE : View.GONE);
            audienceCard.setVisibility(privateEditing
                    || speechAudience == SpeechAudience.EXTERNAL_WITH_MATE
                    || speechAudience == SpeechAudience.USER_DIRECT ? View.VISIBLE : View.GONE);

            boolean showExternalTimeline = active && publicConversation && !privateEditing;
            externalSectionTitle.setVisibility(showExternalTimeline ? View.VISIBLE : View.GONE);
            externalScroll.setVisibility(showExternalTimeline ? View.VISIBLE : View.GONE);

            // Private turns remain persisted, but the normal flow does not compete with a second timeline.
            privateSectionTitle.setVisibility(View.GONE);
            privateScroll.setVisibility(View.GONE);

            modeActions.setVisibility(active && taskIsReady && !privateEditing
                    ? View.VISIBLE : View.GONE);
            utilities.setVisibility(active
                    && session.status() == CommunicationSession.Status.COMPLETED
                    ? View.VISIBLE : View.GONE);
        } else {
            statusText.setVisibility(active ? View.VISIBLE : View.GONE);
            externalSectionTitle.setVisibility(active && publicConversation ? View.VISIBLE : View.GONE);
            externalScroll.setVisibility(active && publicConversation ? View.VISIBLE : View.GONE);
            privateSectionTitle.setVisibility(active && !publicConversation ? View.VISIBLE : View.GONE);
            privateScroll.setVisibility(active && !publicConversation ? View.VISIBLE : View.GONE);
            taskComposerCard.setVisibility((!publicConversation || privateEditing)
                    && speechAudience != SpeechAudience.USER_DIRECT ? View.VISIBLE : View.GONE);
            audienceCard.setVisibility(View.VISIBLE);
            modeActions.setVisibility(active ? View.VISIBLE : View.GONE);
            utilities.setVisibility(active ? View.VISIBLE : View.GONE);
        }

        if (!active) {
            taskText.setVisibility(View.VISIBLE);
            taskText.setText("你想讓 Mate 幫你做什麼？\n\n先把對象、想要的結果和限制交代給 Mate。");
            taskText.setTextSize(20);
            composerLabel.setText("先告訴 Mate 你想做什麼");
            taskInput.setHint("或直接輸入你的需求");
            taskInputButton.setText("交代給 Mate");
            taskVoiceButton.setText("🎙  口頭交代");
            audienceCard.setVisibility(View.GONE);
            modeActions.setVisibility(View.GONE);
            utilities.setVisibility(View.GONE);
            renderAudience(null);
            renderControls(null);
            return;
        }

        String person = session.targetPerson().isEmpty() ? "尚未確認" : session.targetPerson();
        String goal = session.goal().isEmpty() ? "" : session.goal();
        StringBuilder task = new StringBuilder();

        if (inPerson) {
            if (publicConversation) {
                task.append("正在跟 ").append(person).append(" 溝通");
                if (!goal.isEmpty()) task.append("\n").append(goal);
                taskText.setTextSize(14);
            } else if (taskIsReady) {
                task.append("Mate 已理解 ✓\n\n")
                        .append("跟誰：").append(person).append("\n")
                        .append("要做什麼：").append(goal).append("\n")
                        .append("語言：").append(languageLabel(selectedUserLanguage))
                        .append(" → ").append(languageLabel(selectedOtherLanguage));
                taskText.setTextSize(17);
            } else {
                task.append("正在理解你的任務…");
                String brief = latestPrivateBrief(session);
                if (!brief.isEmpty()) task.append("\n\n").append(brief);
                taskText.setTextSize(16);
            }
        } else {
            task.append("任務\n")
                    .append("對象：").append(person).append("\n")
                    .append("目標：").append(goal.isEmpty() ? "Mate 正在整理…" : goal);
            String brief = latestPrivateBrief(session);
            if (!brief.isEmpty()) task.append("\n\n你的交代：").append(brief);
            taskText.setTextSize(14);
        }

        if (session.userDirectControl()) task.append("\n\n你目前已接手，Mate 不會聽，也不會說話。");
        if (!session.pendingUserQuestion().isEmpty()) task.append("\n\n需要你決定：").append(session.pendingUserQuestion());
        if (!session.outcomeSummary().isEmpty()) task.append("\n\n結果：").append(session.outcomeSummary());

        taskText.setText(task.toString());
        taskText.setVisibility(privateEditing ? View.GONE : View.VISIBLE);
        externalSectionTitle.setText(inPerson
                ? "對話 · Mate ↔ " + person
                : "對外紀錄 · Mate ↔ " + person);
        privateSectionTitle.setText("🔒 私人 · 你 ↔ Mate");
        renderTimelines(session, person);

        renderAudience(session);
        renderControls(session);
    }

    private void renderAudience(CommunicationSession session) {
        String person = session == null || session.targetPerson().isEmpty() ? "對方" : session.targetPerson();
        if (speechAudience == SpeechAudience.PRIVATE_TO_MATE) {
            styleAudience(Color.rgb(40, 35, 86), accent);
            audienceTitle.setText(privateReturnAudience == SpeechAudience.EXTERNAL_WITH_MATE
                    ? "🔒 補充 context 給 Mate"
                    : "🎙 你正在交代需求");
            audienceDetail.setText(privateReturnAudience == SpeechAudience.EXTERNAL_WITH_MATE
                    ? "現在只有 Mate 在聽。這段會加入你的私人 context，不會直接說給對方。"
                    : "現在只有 Mate 在聽。把你想做的事、對象和限制說清楚即可。");
        } else if (speechAudience == SpeechAudience.EXTERNAL_WITH_MATE) {
            styleAudience(Color.rgb(18, 56, 48), green);
            audienceTitle.setText("Mate 正在跟 " + person + " 對話");
            audienceDetail.setText("對方語言：" + languageLabel(selectedOtherLanguage)
                    + "。現在麥克風是給對方說話；若要新增條件，請先按「補充 context」。");
        } else if (speechAudience == SpeechAudience.USER_DIRECT) {
            styleAudience(Color.rgb(83, 45, 20), direct);
            audienceTitle.setText("你已接手對話");
            audienceDetail.setText("Mate 現在不會聽，也不會說話。");
        } else if (speechAudience == SpeechAudience.MATE_HANDLING) {
            if (isInPersonMode() && taskReady(session)) {
                styleAudience(Color.rgb(18, 56, 48), green);
                audienceTitle.setText("✓ Mate 已理解");
                audienceDetail.setText("確認內容後，讓 Mate 直接跟對方說。之後仍可隨時補充 context。");
            } else {
                styleAudience(surface2, muted);
                audienceTitle.setText("Mate 正在整理任務");
                audienceDetail.setText("會把你的交代整理成對象與目標；需要的話可以再補充。");
            }
        } else {
            styleAudience(surface2, muted);
            audienceTitle.setText("第 1 步 · 先設定任務");
            audienceDetail.setText("可以按下面的「🎙 用語音交代」，或直接打字。");
        }
    }

    private void styleAudience(int background, int foreground) {
        audienceCard.setBackground(roundRect(background, 16));
        audienceTitle.setTextColor(foreground);
        audienceDetail.setTextColor(text);
    }

    private void renderControls(CommunicationSession session) {
        if (session == null) {
            primaryButton.setText("開始");
            primaryButton.setBackground(roundRect(accent, 11));
            directButton.setVisibility(View.GONE);
            composerLabel.setText("先告訴 Mate 你想做什麼");
            taskVoiceButton.setText("🎙  口頭交代");
            taskInputButton.setText("交代給 Mate");
            return;
        }

        if (session.status() == CommunicationSession.Status.COMPLETED) {
            primaryButton.setText("建立新任務");
            primaryButton.setBackground(roundRect(accent, 11));
            directButton.setVisibility(View.GONE);
            return;
        }

        if (speechAudience == SpeechAudience.USER_DIRECT) {
            primaryButton.setText("讓 Mate 繼續");
            primaryButton.setBackground(roundRect(Color.rgb(5, 150, 105), 11));
            directButton.setVisibility(View.GONE);
            return;
        }

        if (speechAudience == SpeechAudience.PRIVATE_TO_MATE) {
            boolean supplement = privateReturnAudience == SpeechAudience.EXTERNAL_WITH_MATE;
            primaryButton.setText(supplement ? "完成補充，回到對話" : "交代完成");
            primaryButton.setBackground(roundRect(accent, 11));
            directButton.setVisibility(View.GONE);
            composerLabel.setText(supplement
                    ? "補充 context 給 Mate"
                    : "把需求交代給 Mate");
            taskInput.setHint(supplement
                    ? "輸入你要新增的條件或資訊"
                    : "也可以繼續輸入補充內容");
            taskVoiceButton.setText(supplement
                    ? "✓  補充完畢，回到對話"
                    : "✓  我交代完了");
            taskInputButton.setText(supplement
                    ? "送出 context 並回到對話"
                    : "送出文字");
            return;
        }

        taskVoiceButton.setText("🎙  繼續說");
        taskInputButton.setText("送出文字");

        if (speechAudience == SpeechAudience.EXTERNAL_WITH_MATE) {
            primaryButton.setText("＋ 補充 context");
            primaryButton.setBackground(roundRect(accent, 11));
            directButton.setVisibility(View.VISIBLE);
            directButton.setText("我要自己說");
            directButton.setBackground(roundRect(direct, 11));
            return;
        }

        if (session.status() == CommunicationSession.Status.NEEDS_USER_INPUT) {
            primaryButton.setText("回答 Mate");
            primaryButton.setBackground(roundRect(accent, 11));
        } else if (isInPersonMode() && taskReady(session)) {
            String person = session.targetPerson().isEmpty() ? "對方" : session.targetPerson();
            primaryButton.setText("讓 Mate 跟 " + person + " 說");
            primaryButton.setBackground(roundRect(Color.rgb(5, 150, 105), 11));
        } else {
            primaryButton.setText("繼續");
            primaryButton.setBackground(roundRect(accent, 11));
        }

        boolean canSupplement = isInPersonMode() && taskReady(session);
        directButton.setVisibility(canSupplement ? View.VISIBLE : View.GONE);
        directButton.setText(publicConversationActive() ? "我要自己說" : "補充 context");
        directButton.setBackground(roundRect(publicConversationActive() ? direct : surface2, 11));
    }

    private boolean publicConversationActive() {
        return speechAudience == SpeechAudience.EXTERNAL_WITH_MATE
                || speechAudience == SpeechAudience.USER_DIRECT
                || hasExternalMessages(viewedSession);
    }

    private boolean hasExternalMessages(CommunicationSession session) {
        if (session == null) return false;
        for (Message message : session.messages()) {
            if (message.sender == Message.Sender.OTHER_PERSON
                    || (message.sender == Message.Sender.MATE && !"USER".equals(message.recipient))) {
                return true;
            }
        }
        return false;
    }

    private String latestPrivateBrief(CommunicationSession session) {
        if (session == null) return "";
        String value = "";
        for (Message message : session.messages()) {
            if (message.sender == Message.Sender.USER && "MATE".equals(message.recipient)) {
                value = message.content();
            }
        }
        return value;
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
                addBubble(externalTimeline, label, message.content(), mate, false);
                externalCount++;
            } else if (message.sender == Message.Sender.USER || message.sender == Message.Sender.MATE) {
                boolean user = message.sender == Message.Sender.USER;
                addBubble(privateTimeline, user ? "你 → Mate" : "Mate → 你", message.content(), user, true);
                privateCount++;
            }
        }
        if (externalCount == 0) {
            addPlaceholder(externalTimeline,
                    "等待 " + person + " 開口…\n對方實際說話後，才會開始建立對話紀錄。");
        }
        if (privateCount == 0) {
            addPlaceholder(privateTimeline, "你用語音或文字交代給 Mate 的內容會留在這裡，不會原文直接給對方。");
        }
        if (followExternal) scrollToBottom(externalScroll);
        if (followPrivate) scrollToBottom(privateScroll);
    }

    private void addBubble(LinearLayout container, String label, String content, boolean right, boolean privateBubble) {
        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(dp(12), dp(9), dp(12), dp(10));
        int color = privateBubble ? (right ? privateUserSurface : surface2) : (right ? accentSurface : inboundSurface);
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
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
        if (value.contains("input") || value.contains("Connecting")) return amber;
        if (value.contains("Listening") || value.contains("Completed")
                || value.contains("Mate handling") || value.contains("External live")) return green;
        if (value.contains("User direct")) return direct;
        if (value.contains("Error") || value.contains("failed")) return red;
        return muted;
    }

    private String friendlyStatus(String value) {
        if (value == null) return "";
        if (value.contains("Needs your input")) return "需要你決定";
        if (value.contains("Listening")) return "🔒 正在聽你對 Mate 說";
        if (value.contains("Mate is speaking")) return "Mate 正在回覆你";
        if (value.contains("Mate handling")) return "Mate 正在處理";
        if (value.contains("External live")) return "🎙 對方正在跟 Mate 說";
        if (value.contains("Mate speaking externally")) return "Mate 正在對對方說";
        if (value.contains("User direct")) return "🎙 你已接手跟對方說";
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
                : color == direct ? Color.rgb(83, 45, 20)
                : color == red ? Color.rgb(70, 30, 38)
                : surface2;
        statusText.setBackground(roundRect(bgColor, 12));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_AUDIO) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted && pendingHandoffAfterPermission) {
                pendingHandoffAfterPermission = false;
                handToOtherPerson();
                return;
            }
            if (granted && pendingStartAfterPermission) {
                pendingStartAfterPermission = false;
                startRuntime(AppConfig.getApiKey(this), pendingAudienceAfterPermission);
                return;
            }
            pendingHandoffAfterPermission = false;
            pendingStartAfterPermission = false;
            if (!granted) {
                Toast.makeText(this, "開始語音對談需要麥克風權限。你仍可用文字設定任務。", Toast.LENGTH_LONG).show();
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
