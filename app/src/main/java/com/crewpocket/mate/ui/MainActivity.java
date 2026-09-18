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
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.crewpocket.mate.agent.CrewMateRuntime;
import com.crewpocket.mate.config.AppConfig;
import com.crewpocket.mate.model.AudioOutputMode;
import com.crewpocket.mate.model.CommunicationSession;
import com.crewpocket.mate.model.LiveCallState;
import com.crewpocket.mate.model.InterfaceLanguage;
import com.crewpocket.mate.model.Message;
import com.crewpocket.mate.model.SpeechAudience;
import com.crewpocket.mate.storage.SessionStore;
import com.crewpocket.mate.voice.GeminiLiveModelSession;
import com.crewpocket.mate.voice.GeminiTranslationService;
import com.crewpocket.mate.voice.TurnTextAccumulator;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Task-first UI with an explicit speech audience boundary. */
public class MainActivity extends Activity {
    private static final int REQUEST_AUDIO = 701;
    private static final long INPUT_TRANSCRIPT_SETTLE_MS = 900L;
    private static final long PRIVATE_SUPPLEMENT_SETTLE_MS = 1400L;

    private static final String[] LANGUAGE_CODES = new String[]{
            "AUTO", "zh-TW", "en-US", "th-TH", "ja-JP", "ko-KR", "vi-VN",
            "zh-CN", "id-ID", "ms-MY", "es-ES", "fr-FR", "de-DE"
    };
    private static final String[] LANGUAGE_LABELS_ZH = new String[]{
            "自動辨識", "繁體中文", "English", "ไทย", "日本語", "한국어", "Tiếng Việt",
            "简体中文", "Bahasa Indonesia", "Bahasa Melayu", "Español", "Français", "Deutsch"
    };
    private static final String[] LANGUAGE_LABELS_EN = new String[]{
            "Auto detect", "Traditional Chinese", "English", "Thai", "Japanese", "Korean", "Vietnamese",
            "Simplified Chinese", "Indonesian", "Malay", "Spanish", "French", "German"
    };
    private static final String[] LANGUAGE_SHORT_LABELS_ZH = new String[]{
            "自動", "繁中", "EN", "ไทย", "日本語", "한국어", "VI",
            "简中", "ID", "MY", "ES", "FR", "DE"
    };
    private static final String[] LANGUAGE_SHORT_LABELS_EN = new String[]{
            "Auto", "ZH-TW", "EN", "TH", "JA", "KO", "VI",
            "ZH-CN", "ID", "MY", "ES", "FR", "DE"
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
    private Button endConversationButton;
    private Button moreButton;
    private Button settingsButton;
    private LinearLayout callBar;
    private TextView callStateText;
    private Button languageButton;
    private Button audioOutputButton;
    private Button transcriptModeButton;
    private Button callToggleButton;
    private TextView statusText;
    private TextView taskText;
    private LinearLayout taskComposerCard;
    private TextView composerLabel;
    private EditText taskInput;
    private Button taskInputButton;
    private Button taskVoiceButton;
    private LinearLayout modeActions;
    private View bottomActionSpacer;
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
    private GeminiTranslationService translationService;
    private final Set<String> translationInFlight = new HashSet<String>();
    private final Set<String> translationAttempted = new HashSet<String>();
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
    private AudioOutputMode audioOutputMode = AudioOutputMode.MEDIA;
    private InterfaceLanguage interfaceLanguage = InterfaceLanguage.ZH;
    // 0 = bilingual, 1 = translated/user language, 2 = original.
    private int transcriptDisplayMode;
    private boolean oneShotPrivateSupplement;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        sessionStore = new SessionStore(this);
        translationService = new GeminiTranslationService(AppConfig.getApiKey(this));
        selectedUserLanguage = defaultUserLanguage();
        audioOutputMode = AppConfig.getAudioOutputMode(this);
        interfaceLanguage = AppConfig.getInterfaceLanguage(this);
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
        final int bottomSafety = dp(10);
        root.setPadding(baseLeft, baseTop, baseRight, baseBottom + bottomSafety);
        root.setBackgroundColor(bg);
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int left;
                int top;
                int right;
                int bottom;
                if (Build.VERSION.SDK_INT >= 30) {
                    Insets barsAndCutout = insets.getInsets(
                            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    Insets navigation = insets.getInsets(
                            WindowInsets.Type.navigationBars() | WindowInsets.Type.mandatorySystemGestures());
                    left = barsAndCutout.left;
                    top = barsAndCutout.top;
                    right = barsAndCutout.right;
                    bottom = Math.max(barsAndCutout.bottom, navigation.bottom);
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
                        baseBottom + bottom + bottomSafety);
                return insets;
            }
        });
        root.post(new Runnable() {
            @Override public void run() { root.requestApplyInsets(); }
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
        subtitle.setText(ui("把想說的交給 Mate。", "Hand it to Mate."));
        subtitle.setTextSize(12);
        subtitle.setTextColor(muted);
        subtitle.setPadding(0, dp(2), 0, 0);
        titleBox.addView(subtitle);
        top.addView(titleBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        newTaskButton = actionButton(ui("＋ 新任務", "+ New task"), surface2);
        newTaskButton.setTextSize(11);
        newTaskButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestNewTask(); }
        });
        top.addView(newTaskButton, new LinearLayout.LayoutParams(dp(82), dp(38)));

        historyButton = actionButton(ui("紀錄", "History"), surface2);
        historyButton.setTextSize(11);
        historyButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showHistory(); }
        });
        LinearLayout.LayoutParams historyTopLp = new LinearLayout.LayoutParams(dp(58), dp(38));
        historyTopLp.setMargins(dp(6), 0, 0, 0);
        top.addView(historyButton, historyTopLp);

        settingsButton = actionButton(ui("設定", "Settings"), surface2);
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

        languageButton = actionButton(ui("🌐 自動 → 自動", "🌐 Auto → Auto"), surface2);
        languageButton.setTextSize(10);
        languageButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showLanguagePicker(); }
        });
        LinearLayout.LayoutParams languageLp = new LinearLayout.LayoutParams(dp(104), dp(38));
        languageLp.setMargins(dp(6), 0, dp(6), 0);
        callBar.addView(languageButton, languageLp);

        audioOutputButton = actionButton(ui("🔊 媒體", "🔊 Media"), surface2);
        audioOutputButton.setTextSize(10);
        audioOutputButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleAudioOutputMode(); }
        });
        LinearLayout.LayoutParams outputLp = new LinearLayout.LayoutParams(dp(82), dp(38));
        outputLp.setMargins(0, 0, dp(6), 0);
        callBar.addView(audioOutputButton, outputLp);

        transcriptModeButton = actionButton(ui("雙語", "Bilingual"), surface2);
        transcriptModeButton.setTextSize(10);
        transcriptModeButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { cycleTranscriptDisplayMode(); }
        });
        LinearLayout.LayoutParams transcriptLp = new LinearLayout.LayoutParams(dp(64), dp(38));
        transcriptLp.setMargins(0, 0, dp(6), 0);
        callBar.addView(transcriptModeButton, transcriptLp);

        callToggleButton = actionButton(ui("開啟", "Start"), surface2);
        callToggleButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { handleLiveCallToggle(); }
        });
        callBar.addView(callToggleButton, new LinearLayout.LayoutParams(dp(82), dp(38)));

        LinearLayout.LayoutParams callLp = cardLp(dp(10));
        callLp.setMargins(0, dp(12), 0, dp(10));
        root.addView(callBar, callLp);
        renderLanguageControl();
        renderAudioOutputControl();
        renderTranscriptModeControl();
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
        composerLabel.setText(ui("先告訴 Mate 你想做什麼", "Tell Mate what you need first"));
        composerLabel.setTextColor(text);
        composerLabel.setTextSize(16);
        composerLabel.setTypeface(Typeface.DEFAULT_BOLD);
        taskComposerCard.addView(composerLabel);

        taskVoiceButton = actionButton(ui("🎙  口頭交代", "🎙  Speak to Mate"), accent);
        taskVoiceButton.setTextSize(14);
        taskVoiceButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { handlePrimaryAction(); }
        });
        LinearLayout.LayoutParams voiceLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        voiceLp.setMargins(0, dp(12), 0, dp(12));
        taskComposerCard.addView(taskVoiceButton, voiceLp);

        taskInput = new EditText(this);
        taskInput.setHint(ui("或直接輸入，例如：幫我問櫃台能不能延後退房，超過 500 泰銖先問我", "Or type it, e.g. ask the front desk for late checkout; ask me first if it costs over 500 THB"));
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
        taskInputButton = actionButton(ui("送出文字", "Send text"), accentSurface);
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

        externalSectionTitle = sectionTitle(ui("對外紀錄", "External conversation"));
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

        privateSectionTitle = sectionTitle(ui("🔒 私人 · 你 ↔ Mate", "🔒 Private · You ↔ Mate"));
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

        bottomActionSpacer = new View(this);
        bottomActionSpacer.setVisibility(View.GONE);
        root.addView(bottomActionSpacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        modeActions = new LinearLayout(this);
        modeActions.setOrientation(LinearLayout.HORIZONTAL);
        modeActions.setGravity(Gravity.BOTTOM | Gravity.CENTER_VERTICAL);
        modeActions.setClipChildren(false);
        modeActions.setClipToPadding(false);
        modeActions.setPadding(0, dp(4), 0, dp(8));
        primaryButton = actionButton(ui("🔒 交代給 Mate", "🔒 Brief Mate"), accent);
        primaryButton.setTextSize(13);
        primaryButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { handlePrimaryAction(); }
        });
        modeActions.addView(primaryButton, new LinearLayout.LayoutParams(0, dp(52), 1.45f));
        directButton = actionButton(ui("我要自己說", "I'll speak myself"), direct);
        directButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { handleSecondaryAction(); }
        });
        LinearLayout.LayoutParams directLp = new LinearLayout.LayoutParams(0, dp(52), 1f);
        directLp.setMargins(dp(8), 0, 0, 0);
        modeActions.addView(directButton, directLp);

        endConversationButton = actionButton(ui("完成任務", "Complete task"), Color.rgb(5, 150, 105));
        endConversationButton.setTextSize(11);
        endConversationButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmCompleteTask(); }
        });
        LinearLayout.LayoutParams endLp = new LinearLayout.LayoutParams(0, dp(52), 0.92f);
        endLp.setMargins(dp(8), 0, 0, 0);
        modeActions.addView(endConversationButton, endLp);

        moreButton = actionButton("⋯", surface2);
        moreButton.setTextSize(16);
        moreButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showEndTaskOptions(); }
        });
        LinearLayout.LayoutParams moreLp = new LinearLayout.LayoutParams(dp(48), dp(52));
        moreLp.setMargins(dp(8), 0, 0, 0);
        modeActions.addView(moreButton, moreLp);
        LinearLayout.LayoutParams modeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        modeLp.setMargins(0, 0, 0, 0);
        root.addView(modeActions, modeLp);

        utilities = new LinearLayout(this);
        utilities.setVisibility(View.GONE);
        root.addView(utilities);
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
            if (oneShotPrivateSupplement
                    && privateReturnAudience == SpeechAudience.EXTERNAL_WITH_MATE) {
                if (inputTurn.isEmpty()) {
                    Toast.makeText(this, ui("先說你要補充的內容", "Say what you want to add first"), Toast.LENGTH_SHORT).show();
                    return;
                }
                flushInputTurn();
                return;
            }
            flushInputTurn();
            // Stage 1 remains a real two-way private voice conversation until consensus is ready.
            // Keeping PRIVATE_TO_MATE here allows Mate's clarification question to be heard.
            runtime.finalizeTaskBrief();
            status(ui("Mate 正在整理，會直接跟你確認…", "Mate is organizing the brief and will confirm with you…"), amber);
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
        if (viewedSession != null
                && viewedSession.status() == CommunicationSession.Status.NEEDS_USER_INPUT) {
            enterPrivateSupplement();
            return;
        }
        if (runtime != null && speechAudience == SpeechAudience.MATE_HANDLING) {
            runtime.finalizeTaskBrief();
            status(ui("正在整理任務共識…", "Updating task consensus…"), amber);
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
                ui("我的語言：", "My language: ") + languageLabel(selectedUserLanguage),
                ui("對方語言：", "Other person's language: ") + languageLabel(selectedOtherLanguage)
        };
        new AlertDialog.Builder(this)
                .setTitle(ui("對話語言", "Conversation languages"))
                .setItems(choices, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        showLanguageChoices(which == 0);
                    }
                })
                .setNegativeButton(ui("關閉", "Close"), null)
                .show();
    }

    private void showLanguageChoices(final boolean userSide) {
        final String current = userSide ? selectedUserLanguage : selectedOtherLanguage;
        int checked = languageIndex(current);
        new AlertDialog.Builder(this)
                .setTitle(userSide ? ui("我的語言", "My language") : ui("對方語言", "Other person's language"))
                .setSingleChoiceItems(
                        interfaceLanguage == InterfaceLanguage.EN ? LANGUAGE_LABELS_EN : LANGUAGE_LABELS_ZH,
                        checked,
                        new DialogInterface.OnClickListener() {
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
                .setNegativeButton(ui("取消", "Cancel"), null)
                .show();
    }

    private void applySelectedLanguages() {
        synchronized (translationAttempted) { translationAttempted.clear(); }
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

    private String languageLabel(String code) {
        return (interfaceLanguage == InterfaceLanguage.EN
                ? LANGUAGE_LABELS_EN
                : LANGUAGE_LABELS_ZH)[languageIndex(code)];
    }

    private String languageShortLabel(String code) {
        return (interfaceLanguage == InterfaceLanguage.EN
                ? LANGUAGE_SHORT_LABELS_EN
                : LANGUAGE_SHORT_LABELS_ZH)[languageIndex(code)];
    }

    private boolean isEnglishUi() {
        return interfaceLanguage == InterfaceLanguage.EN;
    }

    private String ui(String zh, String en) {
        return isEnglishUi() ? en : zh;
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

    private void toggleAudioOutputMode() {
        audioOutputMode = audioOutputMode == AudioOutputMode.MEDIA
                ? AudioOutputMode.COMMUNICATION
                : AudioOutputMode.MEDIA;
        AppConfig.setAudioOutputMode(this, audioOutputMode);
        if (modelSession != null) {
            modelSession.setAudioOutputMode(audioOutputMode);
        }
        renderAudioOutputControl();
        Toast.makeText(this,
                audioOutputMode == AudioOutputMode.MEDIA ? ui("聲音輸出：媒體", "Audio output: Media") : ui("聲音輸出：通話", "Audio output: Call"),
                Toast.LENGTH_SHORT).show();
    }

    private void renderAudioOutputControl() {
        if (audioOutputButton == null) return;
        audioOutputButton.setText(audioOutputMode == AudioOutputMode.MEDIA
                ? ui("🔊 媒體", "🔊 Media")
                : ui("☎ 通話", "☎ Call"));
    }

    private void cycleTranscriptDisplayMode() {
        transcriptDisplayMode = (transcriptDisplayMode + 1) % 3;
        renderTranscriptModeControl();
        renderSession(viewedSession);
    }

    private void renderTranscriptModeControl() {
        if (transcriptModeButton == null) return;
        transcriptModeButton.setText(transcriptDisplayMode == 0
                ? ui("雙語", "Bilingual")
                : transcriptDisplayMode == 1 ? ui("譯文", "Translation") : ui("原文", "Original"));
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
        updateKeepScreenOn();
        renderLiveCallControl();
    }

    private void updateKeepScreenOn() {
        boolean keepScreenOn = liveCallState == LiveCallState.CONNECTING
                || liveCallState == LiveCallState.ACTIVE;
        if (keepScreenOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void renderLiveCallControl() {
        if (callStateText == null || callToggleButton == null || callBar == null) return;
        switch (liveCallState) {
            case CONNECTING:
                callStateText.setText(ui("AI 通話連線中…", "AI call connecting…"));
                callStateText.setTextColor(amber);
                callToggleButton.setText(ui("取消", "Cancel"));
                callToggleButton.setBackground(roundRect(surface2, 11));
                break;
            case ACTIVE:
                if (speechAudience == SpeechAudience.PRIVATE_TO_MATE
                        && privateReturnAudience == SpeechAudience.EXTERNAL_WITH_MATE) {
                    callStateText.setText(ui("● 私下補充中 · 請說話", "● Private supplement · Speak now"));
                    callStateText.setTextColor(accent);
                } else if (speechAudience == SpeechAudience.PRIVATE_TO_MATE) {
                    callStateText.setText(ui("● 你 ↔ Mate 對話中", "● You ↔ Mate"));
                    callStateText.setTextColor(accent);
                } else if (speechAudience == SpeechAudience.MATE_HANDLING
                        && privateReturnAudience == SpeechAudience.EXTERNAL_WITH_MATE) {
                    callStateText.setText(ui("● Mate 正在更新補充", "● Mate is updating context"));
                    callStateText.setTextColor(amber);
                } else {
                    callStateText.setText(ui("● AI 通話中", "● AI call active"));
                    callStateText.setTextColor(green);
                }
                callToggleButton.setText(ui("結束", "End"));
                callToggleButton.setBackground(roundRect(Color.rgb(127, 29, 29), 11));
                break;
            case ERROR:
                callStateText.setText(ui("AI 通話連線失敗", "AI call failed"));
                callStateText.setTextColor(red);
                callToggleButton.setText(ui("重試", "Retry"));
                callToggleButton.setBackground(roundRect(accent, 11));
                break;
            case OFF:
            default:
                callStateText.setText(ui("AI 通話已關閉", "AI call off"));
                callStateText.setTextColor(muted);
                callToggleButton.setText(ui("開啟", "Start"));
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
            privateReturnAudience = SpeechAudience.MATE_HANDLING;
            ensureRuntime(SpeechAudience.PRIVATE_TO_MATE);
            return;
        }

        runtime.submitPrivateText(value);

        // Typed input is private, but stage 1 stays in PRIVATE_TO_MATE so Mate can reply aloud.
        if (speechAudience == SpeechAudience.PRIVATE_TO_MATE) {
            if (oneShotPrivateSupplement
                    && privateReturnAudience == SpeechAudience.EXTERNAL_WITH_MATE) {
                oneShotPrivateSupplement = false;
                applyAudience(SpeechAudience.MATE_HANDLING);
                runtime.finalizeTaskBrief();
                status(ui("Mate 正在更新補充…", "Mate is updating your supplement…"), amber);
            } else {
                runtime.finalizePrivateSupplementAfterCurrentTurn();
                status(ui("Mate 正在整理，會直接跟你確認…", "Mate is organizing the brief and will confirm with you…"), amber);
            }
        } else {
            status("Mate handling", green);
        }
    }

    private boolean taskReady(CommunicationSession session) {
        return session != null && session.consensusReady()
                && !session.targetPerson().isEmpty() && !session.goal().isEmpty();
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
                && hasExternalMessages(viewedSession))
                ? SpeechAudience.EXTERNAL_WITH_MATE
                : SpeechAudience.MATE_HANDLING;
        oneShotPrivateSupplement = privateReturnAudience == SpeechAudience.EXTERNAL_WITH_MATE;
        if (runtime == null) {
            ensureRuntime(SpeechAudience.PRIVATE_TO_MATE);
            return;
        }
        applyAudience(SpeechAudience.PRIVATE_TO_MATE);
        status(oneShotPrivateSupplement ? ui("請說補充內容，說完會自動繼續", "Say your supplement; the conversation will resume automatically") : "Private context", accent);
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
        renderLiveCallControl();
        renderAudience(viewedSession);
        renderControls(viewedSession);
    }

    private void ensureRuntime(SpeechAudience initialAudience) {
        String key = AppConfig.getApiKey(this);
        if (key.isEmpty()) {
            Toast.makeText(this, ui("先到設定填入 Gemini API key。", "Add your Gemini API key in Settings first."), Toast.LENGTH_SHORT).show();
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
                            runtime.finalizePrivateSupplementAfterCurrentTurn();
                            status(ui("Mate 正在整理，會直接跟你確認…", "Mate is organizing the brief and will confirm with you…"), amber);
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
                        handler.postDelayed(flushInputTurnRunnable,
                                oneShotPrivateSupplement && audience == SpeechAudience.PRIVATE_TO_MATE
                                        ? PRIVATE_SUPPLEMENT_SETTLE_MS
                                        : INPUT_TRANSCRIPT_SETTLE_MS);
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
        modelSession.setAudioOutputMode(audioOutputMode);
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
                        } else if (session.status() == CommunicationSession.Status.NEEDS_USER_INPUT
                                && privateReturnAudience != SpeechAudience.EXTERNAL_WITH_MATE
                                && speechAudience != SpeechAudience.PRIVATE_TO_MATE) {
                            // Stage 1 clarification is a spoken private conversation.
                            applyAudience(SpeechAudience.PRIVATE_TO_MATE);
                            status(ui("Mate 正在跟你確認", "Mate is confirming with you"), accent);
                        } else if (session.consensusReady()
                                && privateReturnAudience == SpeechAudience.EXTERNAL_WITH_MATE
                                && speechAudience == SpeechAudience.MATE_HANDLING) {
                            applyAudience(SpeechAudience.EXTERNAL_WITH_MATE);
                            Toast.makeText(MainActivity.this, ui("共識已更新，繼續對話", "Consensus updated; continuing conversation"), Toast.LENGTH_SHORT).show();
                            status("External live", green);
                        } else if (session.consensusReady()
                                && privateReturnAudience != SpeechAudience.EXTERNAL_WITH_MATE
                                && speechAudience == SpeechAudience.PRIVATE_TO_MATE) {
                            applyAudience(SpeechAudience.MATE_HANDLING);
                            status("Consensus ready", green);
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
            if (oneShotPrivateSupplement
                    && privateReturnAudience == SpeechAudience.EXTERNAL_WITH_MATE) {
                oneShotPrivateSupplement = false;
                target.finalizePrivateSupplementAfterCurrentTurn();
                applyAudience(SpeechAudience.MATE_HANDLING);
                status(ui("Mate 正在更新補充內容…", "Mate is updating the supplement…"), amber);
            }
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
                .setTitle(ui("開始新任務？", "Start a new task?"))
                .setMessage(ui("目前任務會保留在紀錄中，AI 通話會結束。", "The current task will stay in History and the AI call will end."))
                .setPositiveButton(ui("開始新任務", "Start new task"), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        startFreshTask();
                    }
                })
                .setNegativeButton(ui("取消", "Cancel"), null)
                .show();
    }

    private void startFreshTask() {
        if (runtime != null) stopRuntime();
        viewedSession = null;
        speechAudience = SpeechAudience.IDLE;
        privateReturnAudience = SpeechAudience.MATE_HANDLING;
        oneShotPrivateSupplement = false;
        selectedUserLanguage = defaultUserLanguage();
        selectedOtherLanguage = "AUTO";
        pendingTypedBrief = "";
        if (taskInput != null) taskInput.setText("");
        setLiveCallState(LiveCallState.OFF);
        renderLanguageControl();
        renderAudioOutputControl();
        renderSession(null);
        status("Ready", muted);
    }

    private void confirmCompleteTask() {
        if (viewedSession == null) return;
        new AlertDialog.Builder(this)
                .setTitle(ui("完成任務？", "Complete this task?"))
                .setMessage(ui("確認這次任務已經處理完成。之後會保留完整紀錄，但不會再繼續修改這筆任務。", "Confirm that this task is finished. Its full history will be kept and the completed record will no longer be modified."))
                .setPositiveButton(ui("完成任務", "Complete task"), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        completeTaskByUser();
                    }
                })
                .setNegativeButton(ui("取消", "Cancel"), null)
                .show();
    }

    private void showEndTaskOptions() {
        if (viewedSession == null) {
            if (runtime != null) stopRuntime();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(ui("通話選項", "Call options"))
                .setMessage(ui("只停止 Gemini Live 連線，不會把任務標記為完成。之後可以從「紀錄」繼續。", "Stops only the Gemini Live connection. The task will remain unfinished and can be resumed from History."))
                .setPositiveButton(ui("只結束 AI 通話", "End AI call only"), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        stopRuntime();
                    }
                })
                .setNegativeButton(ui("取消", "Cancel"), null)
                .show();
    }

    private void completeTaskByUser() {
        if (speechAudience.routesMicrophoneToMate()) flushInputTurn();
        CommunicationSession session = runtime == null ? viewedSession : runtime.session();
        if (session == null) return;
        session.setCompletionSource("USER");
        if (session.outcomeSummary().isEmpty()) {
            session.setOutcomeSummary(ui("使用者確認任務已完成。", "User confirmed the task is complete."));
        }
        session.setPendingUserQuestion("");
        session.setStatus(CommunicationSession.Status.COMPLETED);
        sessionStore.save(session);
        viewedSession = session;
        if (runtime != null) {
            stopRuntime();
        } else {
            speechAudience = SpeechAudience.IDLE;
            renderSession(session);
        }
        Toast.makeText(this, ui("任務已完成並保留在紀錄", "Task completed and saved to History"), Toast.LENGTH_SHORT).show();
    }

    private void showHistory() {
        if (viewedSession != null) sessionStore.save(viewedSession);
        final List<CommunicationSession> sessions = sessionStore.loadAll();
        if (sessions.isEmpty()) {
            Toast.makeText(this, ui("目前還沒有任務紀錄。", "No task history yet."), Toast.LENGTH_SHORT).show();
            return;
        }

        final LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(18), dp(16), dp(18), dp(14));
        sheet.setBackground(roundRect(surface, 18));

        TextView title = new TextView(this);
        title.setText(ui("任務紀錄", "Task history"));
        title.setTextColor(text);
        title.setTextSize(21);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        sheet.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText(ui("點進任務可查看完整對話；未完成任務可以直接繼續。", "Open a task to view the full conversation. Unfinished tasks can be resumed."));
        subtitle.setTextColor(muted);
        subtitle.setTextSize(11);
        subtitle.setPadding(0, dp(4), 0, dp(10));
        sheet.addView(subtitle);

        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 0, 0, dp(2));

        final ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        scroll.addView(list);

        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int desiredListHeight = Math.max(dp(138), sessions.size() * dp(124));
        int maxListHeight = Math.max(dp(220), (int) (screenHeight * 0.58f));
        int listHeight = Math.min(desiredListHeight, maxListHeight);
        sheet.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, listHeight));

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(sheet)
                .create();

        for (final CommunicationSession session : sessions) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(12), dp(14), dp(12));
            card.setBackground(roundRect(surface2, 14));

            LinearLayout header = new LinearLayout(this);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);

            TextView person = new TextView(this);
            person.setText(session.targetPerson().isEmpty() ? ui("未指定對象", "No target specified") : session.targetPerson());
            person.setTextColor(text);
            person.setTextSize(15);
            person.setTypeface(Typeface.DEFAULT_BOLD);
            header.addView(person, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView state = new TextView(this);
            state.setText(historyStatus(session));
            state.setTextSize(10);
            state.setTypeface(Typeface.DEFAULT_BOLD);
            state.setTextColor(session.status() == CommunicationSession.Status.COMPLETED ? green
                    : session.status() == CommunicationSession.Status.NEEDS_USER_INPUT ? amber : muted);
            state.setPadding(dp(8), dp(4), dp(8), dp(4));
            state.setBackground(roundRect(bg, 9));
            header.addView(state);
            card.addView(header);

            TextView goal = new TextView(this);
            goal.setText(session.goal().isEmpty() ? ui("尚未確認任務目的", "Goal not confirmed yet") : session.goal());
            goal.setTextColor(text);
            goal.setTextSize(13);
            goal.setPadding(0, dp(7), 0, 0);
            card.addView(goal);

            if (!session.outcomeSummary().isEmpty()) {
                TextView outcome = new TextView(this);
                outcome.setText(ui("結果：", "Outcome: ") + session.outcomeSummary());
                outcome.setTextColor(muted);
                outcome.setTextSize(11);
                outcome.setPadding(0, dp(6), 0, 0);
                card.addView(outcome);
            }

            TextView meta = new TextView(this);
            meta.setText(formatHistoryTime(session.updatedAt())
                    + "  ·  " + languageShortLabel(session.userLanguage())
                    + " → " + languageShortLabel(session.otherPersonLanguage()));
            meta.setTextColor(muted);
            meta.setTextSize(10);
            meta.setPadding(0, dp(8), 0, 0);
            card.addView(meta);

            LinearLayout cardActions = new LinearLayout(this);
            cardActions.setOrientation(LinearLayout.HORIZONTAL);
            cardActions.setGravity(Gravity.CENTER_VERTICAL);
            cardActions.setPadding(0, dp(8), 0, 0);

            TextView action = new TextView(this);
            action.setText(session.status() == CommunicationSession.Status.COMPLETED
                    ? ui("查看詳情  ›", "View details  ›")
                    : ui("查看／繼續任務  ›", "View / resume  ›"));
            action.setTextColor(accent);
            action.setTextSize(11);
            action.setTypeface(Typeface.DEFAULT_BOLD);
            cardActions.addView(action, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button remove = actionButton(ui("移除", "Remove"), Color.rgb(88, 38, 48));
            remove.setTextSize(10);
            remove.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    confirmRemoveHistorySession(session, dialog);
                }
            });
            cardActions.addView(remove, new LinearLayout.LayoutParams(dp(58), dp(34)));
            card.addView(cardActions);

            card.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    dialog.dismiss();
                    showHistoryDetails(session);
                }
            });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(4), 0, dp(7));
            list.addView(card, lp);
        }

        Button close = actionButton(ui("關閉", "Close"), surface2);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dialog.dismiss(); }
        });
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        closeLp.setMargins(0, dp(8), 0, 0);
        sheet.addView(close, closeLp);

        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface ignored) { styleHistoryDialog(dialog); }
        });
        dialog.show();
    }

    private String historyStatus(CommunicationSession session) {
        if (session == null) return ui("未知", "Unknown");
        switch (session.status()) {
            case COMPLETED:
                return "USER".equals(session.completionSource()) ? ui("已完成 · 你確認", "Completed · confirmed by you") : ui("已完成", "Completed");
            case NEEDS_USER_INPUT:
                return ui("等待你決定", "Waiting for your decision");
            case ERROR:
                return ui("發生錯誤", "Error");
            case STOPPED:
                return ui("已暫停", "Paused");
            case THINKING:
            default:
                return runtime != null && viewedSession != null
                        && viewedSession.sessionId.equals(session.sessionId)
                        ? ui("進行中", "In progress")
                        : ui("未完成", "Unfinished");
        }
    }

    private String formatHistoryTime(long timestamp) {
        try {
            return new SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(new Date(timestamp));
        } catch (Exception ignored) {
            return "";
        }
    }

    private void showHistoryDetails(final CommunicationSession session) {
        if (session == null) return;
        ensureSessionTranslations(session);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(4), dp(4), dp(4), dp(10));

        TextView state = new TextView(this);
        state.setText(historyStatus(session) + "  ·  " + formatHistoryTime(session.updatedAt()));
        state.setTextColor(session.status() == CommunicationSession.Status.COMPLETED ? green : amber);
        state.setTypeface(Typeface.DEFAULT_BOLD);
        state.setTextSize(12);
        content.addView(state);

        addHistorySection(content, ui("任務", "Task"), session.goal().isEmpty() ? ui("尚未確認目的", "Goal not confirmed") : session.goal());
        if (!session.constraints().isEmpty()) addHistorySection(content, ui("限制／條件", "Constraints"), session.constraints());
        if (!session.escalationBoundary().isEmpty()) {
            addHistorySection(content, ui("需要回來問你", "Ask you before"), session.escalationBoundary());
        }
        if (!session.paymentPreference().isEmpty()) {
            String payment = session.paymentPreference();
            if (!session.paymentFallback().isEmpty()) payment += ui("\n可接受：", "\nAllowed: ") + session.paymentFallback();
            addHistorySection(content, ui("付款", "Payment"), payment);
        }
        if (!session.outcomeSummary().isEmpty()) addHistorySection(content, ui("結果", "Outcome"), session.outcomeSummary());

        TextView language = new TextView(this);
        language.setText(ui("語言  ", "Languages  ") + languageLabel(session.userLanguage())
                + " → " + languageLabel(session.otherPersonLanguage()));
        language.setTextColor(muted);
        language.setTextSize(11);
        language.setPadding(0, dp(10), 0, dp(6));
        content.addView(language);

        if (!session.messages().isEmpty()) {
            TextView title = new TextView(this);
            title.setText(ui("對話紀錄", "Conversation"));
            title.setTextColor(text);
            title.setTextSize(13);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setPadding(0, dp(12), 0, dp(6));
            content.addView(title);

            for (Message message : session.messages()) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(10), dp(8), dp(10), dp(8));
                row.setBackground(roundRect(surface2, 11));

                TextView who = new TextView(this);
                who.setText(historyMessageLabel(session, message));
                who.setTextColor(muted);
                who.setTextSize(9);
                who.setTypeface(Typeface.DEFAULT_BOLD);
                row.addView(who);

                boolean external = isExternalMessage(message);
                String translated = message.translatedText();
                if (external && !translated.isEmpty() && !translated.equals(message.content())) {
                    TextView translatedView = new TextView(this);
                    translatedView.setText(translated);
                    translatedView.setTextColor(text);
                    translatedView.setTextSize(13);
                    translatedView.setPadding(0, dp(4), 0, dp(4));
                    row.addView(translatedView);

                    TextView original = new TextView(this);
                    original.setText(message.content());
                    original.setTextColor(muted);
                    original.setTextSize(11);
                    row.addView(original);
                } else {
                    TextView body = new TextView(this);
                    body.setText(message.content());
                    body.setTextColor(text);
                    body.setTextSize(12);
                    body.setPadding(0, dp(4), 0, 0);
                    row.addView(body);
                }

                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rowLp.setMargins(0, dp(3), 0, dp(5));
                content.addView(row, rowLp);
            }
        }

        final LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(18), dp(16), dp(18), dp(14));
        sheet.setBackground(roundRect(surface, 18));

        TextView header = new TextView(this);
        header.setText(session.targetPerson().isEmpty() ? ui("任務詳情", "Task details") : session.targetPerson());
        header.setTextColor(text);
        header.setTextSize(20);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setPadding(0, 0, 0, dp(8));
        sheet.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        scroll.addView(content);
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int detailHeight = Math.max(dp(280), (int) (screenHeight * 0.56f));
        sheet.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, detailHeight));

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(sheet)
                .create();

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(10), 0, 0);

        Button close = actionButton(ui("關閉", "Close"), surface2);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dialog.dismiss(); }
        });
        actions.addView(close, new LinearLayout.LayoutParams(0, dp(44), 0.8f));

        Button remove = actionButton(ui("移除", "Remove"), Color.rgb(88, 38, 48));
        remove.setTextSize(10);
        remove.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                confirmRemoveHistorySession(session, dialog);
            }
        });
        LinearLayout.LayoutParams removeLp = new LinearLayout.LayoutParams(0, dp(44), 0.78f);
        removeLp.setMargins(dp(8), 0, 0, 0);
        actions.addView(remove, removeLp);

        Button primary = actionButton(
                session.status() == CommunicationSession.Status.COMPLETED
                        ? ui("以此建立新任務", "Create new task from this")
                        : ui("繼續此任務", "Resume task"),
                accentSurface);
        primary.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dialog.dismiss();
                if (session.status() == CommunicationSession.Status.COMPLETED) {
                    requestCloneHistoricalTask(session);
                } else {
                    resumeHistoricalSession(session);
                }
            }
        });
        LinearLayout.LayoutParams primaryLp = new LinearLayout.LayoutParams(0, dp(44), 1.35f);
        primaryLp.setMargins(dp(8), 0, 0, 0);
        actions.addView(primary, primaryLp);
        sheet.addView(actions);

        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface ignored) { styleHistoryDialog(dialog); }
        });
        dialog.show();
    }

    private void confirmRemoveHistorySession(final CommunicationSession session,
                                             final AlertDialog sourceDialog) {
        if (session == null) return;
        boolean active = viewedSession != null
                && viewedSession.sessionId.equals(session.sessionId);
        String message = active
                ? ui("這是目前正在使用的任務。移除後會結束 AI 通話並刪除這筆紀錄，且無法復原。", "This is the active task. Removing it will end the AI call and permanently delete this record.")
                : ui("移除後這筆任務與對話紀錄都會刪除，且無法復原。", "This task and its conversation history will be permanently deleted.");

        new AlertDialog.Builder(this)
                .setTitle(ui("移除這筆紀錄？", "Remove this record?"))
                .setMessage(message)
                .setPositiveButton(ui("移除", "Remove"), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        removeHistorySession(session, sourceDialog);
                    }
                })
                .setNegativeButton(ui("取消", "Cancel"), null)
                .show();
    }

    private void removeHistorySession(CommunicationSession session, AlertDialog sourceDialog) {
        if (session == null) return;
        boolean active = viewedSession != null
                && viewedSession.sessionId.equals(session.sessionId);

        if (active && runtime != null) {
            stopRuntime();
        }

        boolean removed = sessionStore.deleteById(session.sessionId);
        if (!removed) {
            Toast.makeText(this, ui("無法移除這筆紀錄", "Could not remove this record"), Toast.LENGTH_SHORT).show();
            return;
        }

        if (sourceDialog != null) sourceDialog.dismiss();

        if (active) {
            viewedSession = null;
            speechAudience = SpeechAudience.IDLE;
            privateReturnAudience = SpeechAudience.MATE_HANDLING;
            oneShotPrivateSupplement = false;
            pendingTypedBrief = "";
            setLiveCallState(LiveCallState.OFF);
            selectedUserLanguage = defaultUserLanguage();
            selectedOtherLanguage = "AUTO";
            renderLanguageControl();
            renderSession(null);
            status("Ready", muted);
        }

        Toast.makeText(this, ui("紀錄已移除", "Record removed"), Toast.LENGTH_SHORT).show();
        if (!sessionStore.loadAll().isEmpty()) {
            showHistory();
        }
    }

    private void styleHistoryDialog(AlertDialog dialog) {
        if (dialog == null) return;
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setStatusBarColor(bg);
        window.setNavigationBarColor(bg);
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(true);
        }
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int width = Math.min(screenWidth - dp(24), dp(680));
        window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void addHistorySection(LinearLayout container, String label, String value) {
        if (value == null || value.trim().isEmpty()) return;
        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextColor(muted);
        labelView.setTextSize(10);
        labelView.setTypeface(Typeface.DEFAULT_BOLD);
        labelView.setPadding(0, dp(11), 0, dp(3));
        container.addView(labelView);

        TextView valueView = new TextView(this);
        valueView.setText(value);
        valueView.setTextColor(text);
        valueView.setTextSize(13);
        valueView.setLineSpacing(0, 1.15f);
        container.addView(valueView);
    }

    private String historyMessageLabel(CommunicationSession session, Message message) {
        if (message.sender == Message.Sender.USER) return ui("你 → Mate", "You → Mate");
        if (message.sender == Message.Sender.OTHER_PERSON) {
            return (session.targetPerson().isEmpty() ? ui("對方", "Other person") : session.targetPerson()) + " → Mate";
        }
        if (message.sender == Message.Sender.MATE) {
            return "USER".equals(message.recipient) ? ui("Mate → 你", "Mate → You") : "Mate → " + message.recipient;
        }
        return ui("系統", "System");
    }

    private void resumeHistoricalSession(final CommunicationSession selected) {
        if (selected == null || selected.status() == CommunicationSession.Status.COMPLETED) return;
        if (runtime != null && viewedSession != null
                && viewedSession.sessionId.equals(selected.sessionId)) {
            Toast.makeText(this, ui("這就是目前正在進行的任務", "This is the task currently in progress"), Toast.LENGTH_SHORT).show();
            return;
        }
        if (runtime != null && viewedSession != null
                && !viewedSession.sessionId.equals(selected.sessionId)) {
            new AlertDialog.Builder(this)
                    .setTitle(ui("切換任務？", "Switch tasks?"))
                    .setMessage(ui("目前任務會保留在紀錄中，AI 通話會先停止，再切換到這筆任務。", "The current task will remain in History. The AI call will stop before switching to this task."))
                    .setPositiveButton(ui("切換並繼續", "Switch and resume"), new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface dialog, int which) {
                            activateHistoricalSession(selected.sessionId);
                        }
                    })
                    .setNegativeButton(ui("取消", "Cancel"), null)
                    .show();
            return;
        }
        activateHistoricalSession(selected.sessionId);
    }

    private void activateHistoricalSession(String sessionId) {
        if (runtime != null) stopRuntime();
        CommunicationSession session = sessionStore.loadById(sessionId);
        if (session == null || session.status() == CommunicationSession.Status.COMPLETED) return;

        viewedSession = session;
        viewedSession.setUserDirectControl(false);
        selectedUserLanguage = viewedSession.userLanguage();
        selectedOtherLanguage = viewedSession.otherPersonLanguage();
        oneShotPrivateSupplement = false;
        boolean external = hasExternalMessages(viewedSession);
        privateReturnAudience = external ? SpeechAudience.EXTERNAL_WITH_MATE : SpeechAudience.MATE_HANDLING;
        speechAudience = SpeechAudience.MATE_HANDLING;

        if (viewedSession.status() == CommunicationSession.Status.STOPPED
                || viewedSession.status() == CommunicationSession.Status.ERROR) {
            viewedSession.setStatus(CommunicationSession.Status.THINKING);
            sessionStore.save(viewedSession);
        }

        renderLanguageControl();
        renderSession(viewedSession);

        if (viewedSession.status() == CommunicationSession.Status.NEEDS_USER_INPUT
                || !viewedSession.consensusReady()) {
            ensureRuntime(SpeechAudience.PRIVATE_TO_MATE);
        } else if (external) {
            ensureRuntime(SpeechAudience.EXTERNAL_WITH_MATE);
        } else {
            status("Consensus ready", green);
        }
    }

    private void requestCloneHistoricalTask(final CommunicationSession source) {
        if (source == null) return;
        if (runtime != null) {
            new AlertDialog.Builder(this)
                    .setTitle(ui("建立新任務？", "Create a new task?"))
                    .setMessage(ui("目前 AI 通話會停止，新的任務會沿用這筆紀錄的目標與限制，並重新跟你確認。", "The current AI call will stop. The new task will reuse this task's goal and constraints and confirm them with you again."))
                    .setPositiveButton(ui("建立", "Create"), new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface dialog, int which) {
                            cloneHistoricalTask(source);
                        }
                    })
                    .setNegativeButton(ui("取消", "Cancel"), null)
                    .show();
            return;
        }
        cloneHistoricalTask(source);
    }

    private void cloneHistoricalTask(CommunicationSession source) {
        if (runtime != null) stopRuntime();
        CommunicationSession fresh = new CommunicationSession();
        fresh.setConsensus(
                source.targetPersonId(),
                source.targetPerson(),
                source.goal(),
                source.constraints(),
                source.escalationBoundary(),
                source.paymentPreference(),
                source.paymentFallback(),
                false);
        fresh.setLanguages(source.userLanguage(), source.otherPersonLanguage());
        fresh.setStatus(CommunicationSession.Status.THINKING);
        sessionStore.save(fresh);

        viewedSession = fresh;
        selectedUserLanguage = fresh.userLanguage();
        selectedOtherLanguage = fresh.otherPersonLanguage();
        speechAudience = SpeechAudience.MATE_HANDLING;
        privateReturnAudience = SpeechAudience.MATE_HANDLING;
        oneShotPrivateSupplement = false;
        renderLanguageControl();
        renderSession(fresh);
        Toast.makeText(this, ui("已建立新任務，請先重新確認需求", "New task created. Please confirm the requirements again."), Toast.LENGTH_SHORT).show();
    }

    private void showSettings() {
        if (runtime != null) {
            Toast.makeText(this, ui("請先結束目前的即時 session 再修改設定。", "End the current live session before changing settings."), Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(4), dp(22), 0);
        final EditText api = dialogInput("Gemini API key", true);
        api.setText(AppConfig.getApiKey(this));
        box.addView(api, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));

        final InterfaceLanguage[] pendingInterfaceLanguage = new InterfaceLanguage[]{
                AppConfig.getInterfaceLanguage(this)
        };
        final Button interfaceLanguageButton = actionButton(
                ui("介面語言：", "Interface language: ")
                        + (pendingInterfaceLanguage[0] == InterfaceLanguage.EN ? "English" : "中文"),
                surface2);
        interfaceLanguageButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final String[] choices = new String[]{"中文", "English"};
                int checked = pendingInterfaceLanguage[0] == InterfaceLanguage.EN ? 1 : 0;
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(ui("介面語言", "Interface language"))
                        .setSingleChoiceItems(choices, checked, new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialog, int which) {
                                pendingInterfaceLanguage[0] = which == 1
                                        ? InterfaceLanguage.EN
                                        : InterfaceLanguage.ZH;
                                interfaceLanguageButton.setText(
                                        ui("介面語言：", "Interface language: ")
                                                + (pendingInterfaceLanguage[0] == InterfaceLanguage.EN
                                                ? "English" : "中文"));
                                dialog.dismiss();
                            }
                        })
                        .setNegativeButton(ui("取消", "Cancel"), null)
                        .show();
            }
        });
        LinearLayout.LayoutParams interfaceLanguageLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        interfaceLanguageLp.setMargins(0, dp(10), 0, 0);
        box.addView(interfaceLanguageButton, interfaceLanguageLp);

        TextView note = new TextView(this);
        note.setText(ui("現場語音模式：把手機交給對方後，Mate 只會根據麥克風實際聽到的內容回應。", "In-person voice mode: after handing over the phone, Mate only responds to speech actually heard through the microphone."));
        note.setTextColor(Color.DKGRAY);
        note.setTextSize(12);
        note.setPadding(0, dp(10), 0, 0);
        box.addView(note);

        new AlertDialog.Builder(this)
                .setTitle(ui("Crew Mate 設定", "Crew Mate Settings"))
                .setView(box)
                .setPositiveButton(ui("儲存", "Save"), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        AppConfig.setApiKey(MainActivity.this, api.getText().toString());
                        AppConfig.setInterfaceLanguage(MainActivity.this, pendingInterfaceLanguage[0]);
                        translationService = new GeminiTranslationService(AppConfig.getApiKey(MainActivity.this));
                        synchronized (translationAttempted) { translationAttempted.clear(); }
                        if (interfaceLanguage != pendingInterfaceLanguage[0]) {
                            recreate();
                        } else {
                            renderSession(viewedSession);
                        }
                    }
                })
                .setNegativeButton(ui("取消", "Cancel"), null)
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
            // Stage 1 is task alignment. Stage 2 makes the live external dialogue the visual focus.
            boolean stageTwo = publicConversation && !privateEditing;
            boolean needsDecision = active
                    && session.status() == CommunicationSession.Status.NEEDS_USER_INPUT
                    && !session.pendingUserQuestion().isEmpty();

            // Audience/decision card owns in-person state; avoid stacking another status banner.
            statusText.setVisibility(View.GONE);
            taskComposerCard.setVisibility((!active || (!taskIsReady && !stageTwo) || privateEditing)
                    && speechAudience != SpeechAudience.USER_DIRECT ? View.VISIBLE : View.GONE);

            // The large audience card is useful for setup/decisions, but wastes space during live dialogue.
            audienceCard.setVisibility(privateEditing
                    || speechAudience == SpeechAudience.USER_DIRECT
                    || needsDecision
                    || (active && taskIsReady && !stageTwo)
                    ? View.VISIBLE : View.GONE);

            boolean showExternalTimeline = active && stageTwo;
            externalSectionTitle.setVisibility(showExternalTimeline ? View.VISIBLE : View.GONE);
            externalScroll.setVisibility(showExternalTimeline ? View.VISIBLE : View.GONE);

            privateSectionTitle.setVisibility(View.GONE);
            privateScroll.setVisibility(View.GONE);

            boolean showAlignmentAction = active && !privateEditing
                    && !stageTwo
                    && session.status() != CommunicationSession.Status.COMPLETED;
            boolean showActions = showAlignmentAction
                    || needsDecision
                    || speechAudience == SpeechAudience.EXTERNAL_WITH_MATE
                    || speechAudience == SpeechAudience.USER_DIRECT;
            modeActions.setVisibility(showActions ? View.VISIBLE : View.GONE);
            bottomActionSpacer.setVisibility(showActions && !showExternalTimeline
                    ? View.VISIBLE : View.GONE);
            utilities.setVisibility(active
                    && session.status() == CommunicationSession.Status.COMPLETED
                    ? View.VISIBLE : View.GONE);

            // During stage 2 keep only the call state + end action in the top bar.
            languageButton.setVisibility(stageTwo ? View.GONE : View.VISIBLE);
            transcriptModeButton.setVisibility(stageTwo ? View.VISIBLE : View.GONE);
            audioOutputButton.setVisibility(View.VISIBLE);
        } else {
            transcriptModeButton.setVisibility(publicConversation ? View.VISIBLE : View.GONE);
            statusText.setVisibility(active ? View.VISIBLE : View.GONE);
            externalSectionTitle.setVisibility(active && publicConversation ? View.VISIBLE : View.GONE);
            externalScroll.setVisibility(active && publicConversation ? View.VISIBLE : View.GONE);
            privateSectionTitle.setVisibility(active && !publicConversation ? View.VISIBLE : View.GONE);
            privateScroll.setVisibility(active && !publicConversation ? View.VISIBLE : View.GONE);
            taskComposerCard.setVisibility((!publicConversation || privateEditing)
                    && speechAudience != SpeechAudience.USER_DIRECT ? View.VISIBLE : View.GONE);
            audienceCard.setVisibility(View.VISIBLE);
            modeActions.setVisibility(active ? View.VISIBLE : View.GONE);
            bottomActionSpacer.setVisibility(active && !publicConversation
                    ? View.VISIBLE : View.GONE);
            utilities.setVisibility(active ? View.VISIBLE : View.GONE);
        }

        if (!active) {
            taskText.setVisibility(View.VISIBLE);
            taskText.setText(ui("你想讓 Mate 幫你做什麼？\n\n先把對象、想要的結果和限制交代給 Mate。", "What do you want Mate to help with?\n\nTell Mate the target, desired outcome, and constraints first."));
            taskText.setTextSize(20);
            composerLabel.setText(ui("先告訴 Mate 你想做什麼", "Tell Mate what you need first"));
            taskInput.setHint(ui("或直接輸入你的需求", "Or type your request"));
            taskInputButton.setText(ui("交代給 Mate", "Brief Mate"));
            taskVoiceButton.setText(ui("🎙  口頭交代", "🎙  Speak to Mate"));
            audienceCard.setVisibility(View.GONE);
            modeActions.setVisibility(View.GONE);
            bottomActionSpacer.setVisibility(View.GONE);
            utilities.setVisibility(View.GONE);
            transcriptModeButton.setVisibility(View.GONE);
            renderAudience(null);
            renderControls(null);
            return;
        }

        String person = session.targetPerson().isEmpty() ? ui("尚未確認", "Not confirmed") : session.targetPerson();
        String goal = session.goal().isEmpty() ? ui("尚未確認", "Not confirmed") : session.goal();
        String constraints = session.constraints().isEmpty() ? ui("尚未設定", "Not set") : session.constraints();
        String boundary = session.escalationBoundary().isEmpty() ? ui("尚未設定", "Not set") : session.escalationBoundary();
        boolean hasPaymentPolicy = !session.paymentPreference().isEmpty()
                || !session.paymentFallback().isEmpty();
        String paymentPreference = session.paymentPreference();
        String paymentFallback = session.paymentFallback();
        StringBuilder task = new StringBuilder();

        if (publicConversation && !privateEditing) {
            task.append("Mate ↔ ").append(person)
                    .append("  ·  ").append(languageLabel(selectedOtherLanguage))
                    .append(ui("\n目的：", "\nGoal: ")).append(goal);
            if (hasPaymentPolicy) {
                if (!paymentPreference.isEmpty()) {
                    task.append(ui("\n付款：", "\nPayment: ")).append(paymentPreference);
                }
                if (!paymentFallback.isEmpty()) {
                    task.append(ui(" · 可接受：", " · Allowed: ")).append(paymentFallback);
                }
            }
            taskText.setTextSize(13);
            taskText.setBackground(roundRect(surface2, 12));
        } else {
            task.append(ui("本次任務共識", "Task consensus"))
                    .append(taskIsReady ? ui("  ✓ 已對齊", "  ✓ Aligned") : ui("  · 尚未完成", "  · Incomplete"))
                    .append(ui("\n\n對象：", "\n\nTarget: ")).append(person)
                    .append(ui("\n目的：", "\nGoal: ")).append(goal)
                    .append(ui("\n限制／條件：", "\nConstraints: ")).append(constraints);
            if (hasPaymentPolicy) {
                task.append(ui("\n付款方式：", "\nPayment method: "))
                        .append(paymentPreference.isEmpty() ? ui("未指定", "Not specified") : paymentPreference);
                task.append(ui("\n付款替代：", "\nPayment fallback: "))
                        .append(paymentFallback.isEmpty()
                                ? ui("未授權；不同付款方式要先問你", "Not authorized; ask you before changing payment method")
                                : paymentFallback);
            }
            task.append(ui("\n需要回來問你：", "\nAsk you before: ")).append(boundary)
                    .append(ui("\n語言：", "\nLanguages: ")).append(languageLabel(selectedUserLanguage))
                    .append(" → ").append(languageLabel(selectedOtherLanguage));
            taskText.setTextSize(taskIsReady ? 15 : 14);
            taskText.setBackground(roundRect(surface, 18));
        }

        if (!session.pendingUserQuestion().isEmpty() && !publicConversation) {
            task.append(ui("\n\nMate 想確認：", "\n\nMate wants to confirm: ")).append(session.pendingUserQuestion());
        }

        if (session.userDirectControl()) task.append(ui("\n\n你目前已接手，Mate 不會聽，也不會說話。", "\n\nYou have taken over. Mate will not listen or speak."));
        if (!session.pendingUserQuestion().isEmpty() && !publicConversation) {
            task.append(ui("\n\n需要你決定：", "\n\nNeeds your decision: ")).append(session.pendingUserQuestion());
        }
        if (!session.outcomeSummary().isEmpty()) task.append(ui("\n\n結果：", "\n\nOutcome: ")).append(session.outcomeSummary());

        taskText.setText(task.toString());
        taskText.setVisibility(View.VISIBLE);
        externalSectionTitle.setText(inPerson
                ? ui("即時對話 · ", "Live conversation · ") + (transcriptDisplayMode == 0 ? ui("雙語", "Bilingual") : transcriptDisplayMode == 1 ? ui("譯文", "Translation") : ui("原文", "Original"))
                : ui("對外紀錄 · Mate ↔ ", "External conversation · Mate ↔ ") + person);
        privateSectionTitle.setText(ui("🔒 私人 · 你 ↔ Mate", "🔒 Private · You ↔ Mate"));
        renderTimelines(session, person);

        renderAudience(session);
        renderControls(session);
    }

    private void renderAudience(CommunicationSession session) {
        String person = session == null || session.targetPerson().isEmpty() ? ui("對方", "Other person") : session.targetPerson();
        if (speechAudience == SpeechAudience.PRIVATE_TO_MATE) {
            styleAudience(Color.rgb(40, 35, 86), accent);
            audienceTitle.setText(privateReturnAudience == SpeechAudience.EXTERNAL_WITH_MATE
                    ? ui("🔒 補充 context 給 Mate", "🔒 Add private context for Mate")
                    : ui("🎙 你正在交代需求", "🎙 You are briefing Mate"));
            audienceDetail.setText(privateReturnAudience == SpeechAudience.EXTERNAL_WITH_MATE
                    ? ui("現在只有 Mate 在聽。直接說你要補充的內容；停下約 1.4 秒後會自動更新共識並回到對話。", "Only Mate is listening. Say what you want to add; after about 1.4 seconds of silence, Mate will update the consensus and return to the conversation.")
                    : ui("現在只有 Mate 在聽。把你想做的事、對象和限制說清楚即可。", "Only Mate is listening. Explain what you want, who it concerns, and any constraints."));
        } else if (speechAudience == SpeechAudience.EXTERNAL_WITH_MATE) {
            styleAudience(surface2, green);
            audienceTitle.setText("● Mate ↔ " + person);
            audienceDetail.setText(ui("需要新增條件時按「補充 context」。", "Tap “Add context” when you need to add a condition."));
        } else if (speechAudience == SpeechAudience.USER_DIRECT) {
            styleAudience(Color.rgb(83, 45, 20), direct);
            audienceTitle.setText(ui("你已接手對話", "You took over the conversation"));
            audienceDetail.setText(ui("Mate 現在不會聽，也不會說話。", "Mate is not listening or speaking right now."));
        } else if (speechAudience == SpeechAudience.MATE_HANDLING) {
            if (session != null && session.status() == CommunicationSession.Status.NEEDS_USER_INPUT
                    && !session.pendingUserQuestion().isEmpty()) {
                styleAudience(Color.rgb(72, 53, 18), amber);
                audienceTitle.setText(ui("Mate 想確認一件事", "Mate needs to confirm something"));
                audienceDetail.setText(session.pendingUserQuestion());
            } else if (isInPersonMode() && taskReady(session)) {
                styleAudience(Color.rgb(18, 56, 48), green);
                audienceTitle.setText(ui("✓ 你和 Mate 已對齊", "✓ You and Mate are aligned"));
                audienceDetail.setText(ui("確認下方共識後，按「讓 Mate 跟 ", "Review the consensus below, then tap “Let Mate talk to ") + person + ui(" 說」進入第二階段。", "” to enter stage two."));
            } else {
                styleAudience(surface2, muted);
                audienceTitle.setText(ui("正在跟 Mate 對齊需求", "Aligning requirements with Mate"));
                audienceDetail.setText(ui("Mate 會先確認對象、目的、限制與需要回來問你的情況；資訊不足時會直接問你。", "Mate will confirm the target, goal, constraints, and when it should ask you. If something important is missing, Mate will ask directly."));
            }
        } else {
            styleAudience(surface2, muted);
            audienceTitle.setText(ui("第 1 步 · 先設定任務", "Step 1 · Set up the task"));
            audienceDetail.setText(ui("可以按下面的「🎙 用語音交代」，或直接打字。", "Use the voice button below or type your request."));
        }
    }

    private void styleAudience(int background, int foreground) {
        audienceCard.setBackground(roundRect(background, 16));
        audienceTitle.setTextColor(foreground);
        audienceDetail.setTextColor(text);
    }

    private void renderControls(CommunicationSession session) {
        if (session == null) {
            primaryButton.setText(ui("開始", "Start"));
            primaryButton.setBackground(roundRect(accent, 11));
            directButton.setVisibility(View.GONE);
            endConversationButton.setVisibility(View.GONE);
            moreButton.setVisibility(View.GONE);
            composerLabel.setText(ui("先告訴 Mate 你想做什麼", "Tell Mate what you need first"));
            taskVoiceButton.setText(ui("🎙  口頭交代", "🎙  Speak to Mate"));
            taskInputButton.setText(ui("交代給 Mate", "Brief Mate"));
            return;
        }

        if (session.status() == CommunicationSession.Status.COMPLETED) {
            primaryButton.setText(ui("建立新任務", "Create new task"));
            primaryButton.setBackground(roundRect(accent, 11));
            directButton.setVisibility(View.GONE);
            endConversationButton.setVisibility(View.GONE);
            moreButton.setVisibility(View.GONE);
            return;
        }

        if (speechAudience == SpeechAudience.USER_DIRECT) {
            primaryButton.setText(ui("讓 Mate 繼續", "Let Mate continue"));
            primaryButton.setBackground(roundRect(Color.rgb(5, 150, 105), 11));
            directButton.setVisibility(View.GONE);
            endConversationButton.setVisibility(View.VISIBLE);
            moreButton.setVisibility(View.VISIBLE);
            return;
        }

        if (speechAudience == SpeechAudience.PRIVATE_TO_MATE) {
            boolean supplement = privateReturnAudience == SpeechAudience.EXTERNAL_WITH_MATE;
            primaryButton.setText(supplement ? ui("說完會自動繼續", "Auto-resume after you finish") : ui("我交代完了 · 請 Mate 整理", "I'm done · Let Mate organize"));
            primaryButton.setBackground(roundRect(accent, 11));
            directButton.setVisibility(View.GONE);
            endConversationButton.setVisibility(View.GONE);
            moreButton.setVisibility(View.GONE);
            composerLabel.setText(supplement
                    ? ui("補充 context 給 Mate", "Add context for Mate")
                    : ui("把需求交代給 Mate", "Brief Mate"));
            taskInput.setHint(supplement
                    ? ui("輸入你要新增的條件或資訊", "Type the condition or information to add")
                    : ui("也可以繼續輸入補充內容", "You can also type more context"));
            taskVoiceButton.setText(supplement
                    ? ui("🎙  正在聽你的補充", "🎙  Listening to your supplement")
                    : ui("✓  我交代完了 · 整理共識", "✓  I'm done · Organize consensus"));
            taskInputButton.setText(supplement
                    ? ui("送出補充並繼續", "Send supplement and continue")
                    : ui("送出文字", "Send text"));
            return;
        }

        taskVoiceButton.setText(ui("🎙  繼續說", "🎙  Continue speaking"));
        taskInputButton.setText(ui("送出文字", "Send text"));

        if (speechAudience == SpeechAudience.EXTERNAL_WITH_MATE) {
            primaryButton.setText(ui("＋ 補充", "+ Add context"));
            primaryButton.setBackground(roundRect(accent, 11));
            directButton.setVisibility(View.VISIBLE);
            directButton.setText(ui("我要自己說", "I'll speak myself"));
            directButton.setBackground(roundRect(direct, 11));
            endConversationButton.setVisibility(View.VISIBLE);
            moreButton.setVisibility(View.VISIBLE);
            return;
        }

        if (session.status() == CommunicationSession.Status.NEEDS_USER_INPUT) {
            primaryButton.setText(ui("回答 Mate 的問題", "Answer Mate"));
            primaryButton.setBackground(roundRect(accent, 11));
            endConversationButton.setVisibility(hasExternalMessages(session) ? View.VISIBLE : View.GONE);
            moreButton.setVisibility(hasExternalMessages(session) ? View.VISIBLE : View.GONE);
        } else if (isInPersonMode() && taskReady(session)) {
            String person = session.targetPerson().isEmpty() ? ui("對方", "Other person") : session.targetPerson();
            primaryButton.setText(ui("第 2 步 · 讓 Mate 跟 ", "Step 2 · Let Mate talk to ") + person);
            primaryButton.setBackground(roundRect(Color.rgb(5, 150, 105), 11));
        } else {
            primaryButton.setText(ui("✓ 我交代完了 · 請 Mate 整理", "✓ I'm done · Let Mate organize"));
            primaryButton.setBackground(roundRect(accent, 11));
        }

        boolean canSupplement = isInPersonMode() && taskReady(session);
        if (session.status() != CommunicationSession.Status.NEEDS_USER_INPUT) {
            endConversationButton.setVisibility(View.GONE);
            moreButton.setVisibility(View.GONE);
        }
        directButton.setVisibility(canSupplement ? View.VISIBLE : View.GONE);
        directButton.setText(publicConversationActive() ? ui("我要自己說", "I'll speak myself") : ui("補充 context", "Add context"));
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
            boolean external = isExternalMessage(message);
            if (external) {
                ensureMessageTranslation(session, message);
                boolean mate = message.sender == Message.Sender.MATE;
                String label = mate ? "Mate → " + person : person + " → Mate";
                addExternalBubble(externalTimeline, label, message, mate);
                externalCount++;
            } else if (message.sender == Message.Sender.USER || message.sender == Message.Sender.MATE) {
                boolean user = message.sender == Message.Sender.USER;
                addBubble(privateTimeline, user ? ui("你 → Mate", "You → Mate") : ui("Mate → 你", "Mate → You"), message.content(), user, true);
                privateCount++;
            }
        }
        if (externalCount == 0) {
            addPlaceholder(externalTimeline,
                    ui("等待 ", "Waiting for ") + person + ui(" 開口…\n對方實際說話後，才會開始建立對話紀錄。", " to speak…\nConversation history starts only after the other person actually speaks."));
        }
        if (privateCount == 0) {
            addPlaceholder(privateTimeline, ui("你用語音或文字交代給 Mate 的內容會留在這裡，不會原文直接給對方。", "Your private voice/text brief stays here and is not shown verbatim to the other person."));
        }
        if (followExternal) scrollToBottom(externalScroll);
        if (followPrivate) scrollToBottom(privateScroll);
    }

    private boolean isExternalMessage(Message message) {
        return message != null && (message.sender == Message.Sender.OTHER_PERSON
                || (message.sender == Message.Sender.MATE && !"USER".equals(message.recipient)));
    }

    private String effectiveUserLanguage(CommunicationSession session) {
        String language = session == null ? selectedUserLanguage : session.userLanguage();
        if (language == null || language.trim().isEmpty() || "AUTO".equalsIgnoreCase(language)) {
            return defaultUserLanguage();
        }
        return language;
    }

    private void ensureSessionTranslations(CommunicationSession session) {
        if (session == null) return;
        for (Message message : session.messages()) {
            if (isExternalMessage(message)) ensureMessageTranslation(session, message);
        }
    }

    private void ensureMessageTranslation(final CommunicationSession session, final Message message) {
        if (session == null || message == null || !isExternalMessage(message)) return;
        final String targetLanguage = effectiveUserLanguage(session);
        String sourceLanguage = message.originalLanguage().isEmpty()
                ? session.otherPersonLanguage()
                : message.originalLanguage();
        if (sourceLanguage == null || sourceLanguage.trim().isEmpty()) sourceLanguage = "AUTO";
        final String source = sourceLanguage;

        if (!message.translatedText().isEmpty()
                && targetLanguage.equalsIgnoreCase(message.translatedLanguage())) return;

        if (!"AUTO".equalsIgnoreCase(source) && source.equalsIgnoreCase(targetLanguage)) {
            message.setTranslation(message.content(), source, targetLanguage);
            sessionStore.save(session);
            return;
        }

        synchronized (translationAttempted) {
            if (translationAttempted.contains(message.id)) return;
            translationAttempted.add(message.id);
        }
        synchronized (translationInFlight) {
            translationInFlight.add(message.id);
        }

        GeminiTranslationService service = translationService;
        if (service == null) {
            synchronized (translationInFlight) { translationInFlight.remove(message.id); }
            return;
        }

        service.translate(message.content(), source, targetLanguage, new GeminiTranslationService.Listener() {
            @Override public void onTranslated(final String translatedText) {
                synchronized (translationInFlight) { translationInFlight.remove(message.id); }
                message.setTranslation(translatedText, source, targetLanguage);
                sessionStore.save(session);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (viewedSession != null && viewedSession.sessionId.equals(session.sessionId)) {
                            renderSession(viewedSession);
                        }
                    }
                });
            }

            @Override public void onError() {
                synchronized (translationInFlight) { translationInFlight.remove(message.id); }
            }
        });
    }

    private void addExternalBubble(LinearLayout container, String label, Message message, boolean right) {
        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(dp(12), dp(9), dp(12), dp(10));
        bubble.setBackground(roundRect(right ? accentSurface : inboundSurface, 14));

        TextView meta = new TextView(this);
        meta.setText(label);
        meta.setTextSize(10);
        meta.setTextColor(muted);
        meta.setTypeface(Typeface.DEFAULT_BOLD);
        bubble.addView(meta);

        String original = message.content() == null ? "" : message.content();
        String translated = message.translatedText() == null ? "" : message.translatedText();
        boolean hasDistinctTranslation = !translated.isEmpty() && !translated.equals(original);

        if (transcriptDisplayMode == 2) {
            TextView originalView = new TextView(this);
            originalView.setText(original);
            originalView.setTextSize(13);
            originalView.setTextColor(text);
            originalView.setLineSpacing(0, 1.16f);
            originalView.setPadding(0, dp(4), 0, 0);
            bubble.addView(originalView);
        } else if (transcriptDisplayMode == 1) {
            TextView translatedView = new TextView(this);
            translatedView.setText(hasDistinctTranslation ? translated : original);
            translatedView.setTextSize(14);
            translatedView.setTextColor(text);
            translatedView.setLineSpacing(0, 1.16f);
            translatedView.setPadding(0, dp(4), 0, 0);
            bubble.addView(translatedView);
            if (!hasDistinctTranslation && isTranslationPending(message)) {
                TextView pending = new TextView(this);
                pending.setText(ui("翻譯中…", "Translating…"));
                pending.setTextSize(9);
                pending.setTextColor(muted);
                pending.setPadding(0, dp(4), 0, 0);
                bubble.addView(pending);
            }
        } else {
            TextView translatedView = new TextView(this);
            translatedView.setText(hasDistinctTranslation ? translated : original);
            translatedView.setTextSize(14);
            translatedView.setTextColor(text);
            translatedView.setLineSpacing(0, 1.16f);
            translatedView.setPadding(0, dp(4), 0, 0);
            bubble.addView(translatedView);

            if (hasDistinctTranslation) {
                TextView originalLabel = new TextView(this);
                originalLabel.setText(ui("原文", "Original"));
                originalLabel.setTextSize(9);
                originalLabel.setTextColor(muted);
                originalLabel.setTypeface(Typeface.DEFAULT_BOLD);
                originalLabel.setPadding(0, dp(7), 0, dp(2));
                bubble.addView(originalLabel);

                TextView originalView = new TextView(this);
                originalView.setText(original);
                originalView.setTextSize(11);
                originalView.setTextColor(muted);
                originalView.setLineSpacing(0, 1.12f);
                bubble.addView(originalView);
            } else if (isTranslationPending(message)) {
                TextView pending = new TextView(this);
                pending.setText(ui("翻譯中…", "Translating…"));
                pending.setTextSize(9);
                pending.setTextColor(muted);
                pending.setPadding(0, dp(5), 0, 0);
                bubble.addView(pending);
            }
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = right ? Gravity.END : Gravity.START;
        lp.setMargins(right ? dp(34) : 0, dp(4), right ? 0 : dp(34), dp(5));
        bubble.setMinimumWidth(dp(130));
        bubble.setLayoutParams(lp);
        container.addView(bubble);
    }

    private boolean isTranslationPending(Message message) {
        synchronized (translationInFlight) {
            return message != null && translationInFlight.contains(message.id);
        }
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
        if (value.contains("Needs your input")) return ui("需要你決定", "Needs your decision");
        if (value.contains("Listening")) return ui("🔒 正在聽你對 Mate 說", "🔒 Listening to you");
        if (value.contains("Mate is speaking")) return ui("Mate 正在回覆你", "Mate is replying to you");
        if (value.contains("Mate handling")) return ui("Mate 正在處理", "Mate is working");
        if (value.contains("External live")) return ui("🎙 對方正在跟 Mate 說", "🎙 Other person is speaking to Mate");
        if (value.contains("Mate speaking externally")) return ui("Mate 正在對對方說", "Mate is speaking to the other person");
        if (value.contains("User direct")) return ui("🎙 你已接手跟對方說", "🎙 You took over the conversation");
        if (value.contains("Connecting")) return ui("連線中", "Connecting");
        if (value.contains("Completed")) return ui("已完成", "Completed");
        if (value.contains("Paused") || value.contains("Stopped")) return ui("已暫停", "Paused");
        if (value.contains("Thinking")) return ui("Mate 正在處理", "Mate is working");
        if (value.contains("Error")) return ui("發生錯誤", "Error");
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
                Toast.makeText(this, ui("開始語音對談需要麥克風權限。你仍可用文字設定任務。", "Microphone permission is required for voice conversation. You can still set up the task with text."), Toast.LENGTH_LONG).show();
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
