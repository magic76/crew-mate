package com.crewpocket.mate.ui;

import android.app.Activity;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.crewpocket.mate.ui.view.BottomActionDock;
import com.crewpocket.mate.ui.view.ConversationPanel;
import com.crewpocket.mate.ui.view.CrewMateUiKit;
import com.crewpocket.mate.ui.view.TaskSetupPanel;

public final class CrewMateScreenBuilder {
    public interface Texts {
        String ui(String zh, String en);
    }

    public interface Actions {
        void onNewTask();
        void onHistory();
        void onSettings();
        void onLanguage();
        void onAudioOutput();
        void onTranscriptMode();
        void onCallToggle();
        void onPrimaryAction();
        void onSecondaryAction();
        void onCompleteTask();
        void onMore();
        void onSubmitTypedBrief();
    }

    public static final class Binding {
        public LinearLayout root;
        public Button primaryButton;
        public Button directButton;
        public Button endConversationButton;
        public Button moreButton;
        public Button settingsButton;
        public LinearLayout callBar;
        public TextView callStateText;
        public Button languageButton;
        public Button audioOutputButton;
        public Button transcriptModeButton;
        public Button callToggleButton;
        public TextView statusText;
        public TextView taskText;
        public LinearLayout taskComposerCard;
        public TextView composerLabel;
        public EditText taskInput;
        public Button taskInputButton;
        public Button taskVoiceButton;
        public LinearLayout modeActions;
        public View bottomActionSpacer;
        public LinearLayout utilities;
        public Button newTaskButton;
        public Button historyButton;
        public LinearLayout audienceCard;
        public TextView audienceTitle;
        public TextView audienceDetail;
        public TextView externalSectionTitle;
        public TextView privateSectionTitle;
        public LinearLayout externalTimeline;
        public LinearLayout privateTimeline;
        public ScrollView externalScroll;
        public ScrollView privateScroll;
    }

    private final Activity activity;
    private final CrewMateUiKit kit;
    private final Texts texts;
    private final Actions actions;

    public CrewMateScreenBuilder(
            Activity activity,
            CrewMateUiKit kit,
            Texts texts,
            Actions actions) {
        this.activity = activity;
        this.kit = kit;
        this.texts = texts;
        this.actions = actions;
    }

    public Binding build() {
        Binding binding = new Binding();
        LinearLayout root = buildRoot();
        binding.root = root;

        buildTopBar(root, binding);
        buildCallBar(root, binding);

        binding.statusText = new TextView(activity);
        binding.statusText.setText("Ready");
        binding.statusText.setTextColor(kit.muted);
        binding.statusText.setTextSize(12);
        binding.statusText.setTypeface(Typeface.DEFAULT_BOLD);
        binding.statusText.setPadding(
                kit.dp(12), kit.dp(8), kit.dp(12), kit.dp(8));
        binding.statusText.setBackground(kit.roundRect(kit.surface2, 12));
        LinearLayout.LayoutParams statusLp = kit.cardLp(10);
        statusLp.setMargins(0, kit.dp(14), 0, kit.dp(10));
        root.addView(binding.statusText, statusLp);

        binding.taskText = kit.cardText(14);
        binding.taskText.setBackground(kit.roundRect(kit.surface, 18));
        root.addView(binding.taskText, kit.cardLp(10));

        TaskSetupPanel setup = new TaskSetupPanel(
                activity,
                kit,
                new TaskSetupPanel.Texts() {
                    @Override public String ui(String zh, String en) {
                        return texts.ui(zh, en);
                    }
                },
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        actions.onPrimaryAction();
                    }
                },
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        actions.onSubmitTypedBrief();
                    }
                });
        binding.taskComposerCard = setup;
        binding.composerLabel = setup.label;
        binding.taskInput = setup.input;
        binding.taskInputButton = setup.inputButton;
        binding.taskVoiceButton = setup.voiceButton;
        root.addView(setup, kit.cardLp(10));

        buildAudienceCard(root, binding);

        ConversationPanel conversation = new ConversationPanel(
                activity,
                kit,
                new ConversationPanel.Texts() {
                    @Override public String ui(String zh, String en) {
                        return texts.ui(zh, en);
                    }
                });
        conversation.attachTo(root, kit);
        binding.externalSectionTitle = conversation.externalSectionTitle;
        binding.externalTimeline = conversation.externalTimeline;
        binding.externalScroll = conversation.externalScroll;
        binding.privateSectionTitle = conversation.privateSectionTitle;
        binding.privateTimeline = conversation.privateTimeline;
        binding.privateScroll = conversation.privateScroll;

        binding.bottomActionSpacer = BottomActionDock.createSpacer(activity);
        root.addView(
                binding.bottomActionSpacer,
                BottomActionDock.spacerLayoutParams());

        BottomActionDock dock = new BottomActionDock(
                activity,
                kit,
                new BottomActionDock.Texts() {
                    @Override public String ui(String zh, String en) {
                        return texts.ui(zh, en);
                    }
                },
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        actions.onPrimaryAction();
                    }
                },
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        actions.onSecondaryAction();
                    }
                },
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        actions.onCompleteTask();
                    }
                },
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        actions.onMore();
                    }
                });
        binding.modeActions = dock;
        binding.primaryButton = dock.primaryButton;
        binding.directButton = dock.directButton;
        binding.endConversationButton = dock.completeButton;
        binding.moreButton = dock.moreButton;
        root.addView(dock, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        binding.utilities = new LinearLayout(activity);
        binding.utilities.setVisibility(View.GONE);
        root.addView(binding.utilities);

        return binding;
    }

    private LinearLayout buildRoot() {
        final LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);

        final int baseLeft = kit.dp(18);
        final int baseTop = kit.dp(16);
        final int baseRight = kit.dp(18);
        final int baseBottom = kit.dp(16);
        final int bottomSafety = kit.dp(10);

        root.setPadding(
                baseLeft,
                baseTop,
                baseRight,
                baseBottom + bottomSafety);
        root.setBackgroundColor(kit.bg);

        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(
                    View v,
                    WindowInsets insets) {
                int left;
                int top;
                int right;
                int bottom;

                if (Build.VERSION.SDK_INT >= 30) {
                    Insets barsAndCutout = insets.getInsets(
                            WindowInsets.Type.systemBars()
                                    | WindowInsets.Type.displayCutout());
                    Insets navigation = insets.getInsets(
                            WindowInsets.Type.navigationBars()
                                    | WindowInsets.Type.mandatorySystemGestures());
                    left = barsAndCutout.left;
                    top = barsAndCutout.top;
                    right = barsAndCutout.right;
                    bottom = Math.max(
                            barsAndCutout.bottom,
                            navigation.bottom);
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
            @Override public void run() {
                root.requestApplyInsets();
            }
        });

        return root;
    }

    private void buildTopBar(LinearLayout root, Binding binding) {
        LinearLayout top = new LinearLayout(activity);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleBox = new LinearLayout(activity);
        titleBox.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(activity);
        title.setText("Crew Mate");
        title.setTextSize(26);
        title.setTextColor(kit.text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleBox.addView(title);

        TextView subtitle = new TextView(activity);
        subtitle.setText(texts.ui(
                "把想說的交給 Mate。",
                "Hand it to Mate."));
        subtitle.setTextSize(12);
        subtitle.setTextColor(kit.muted);
        subtitle.setPadding(0, kit.dp(2), 0, 0);
        titleBox.addView(subtitle);

        top.addView(
                titleBox,
                new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f));

        binding.newTaskButton = kit.actionButton(
                texts.ui("＋ 新任務", "+ New task"),
                kit.surface2);
        binding.newTaskButton.setTextSize(11);
        binding.newTaskButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                actions.onNewTask();
            }
        });
        top.addView(
                binding.newTaskButton,
                new LinearLayout.LayoutParams(
                        kit.dp(82),
                        kit.dp(38)));

        binding.historyButton = kit.actionButton(
                texts.ui("紀錄", "History"),
                kit.surface2);
        binding.historyButton.setTextSize(11);
        binding.historyButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                actions.onHistory();
            }
        });
        LinearLayout.LayoutParams historyLp = new LinearLayout.LayoutParams(
                kit.dp(58),
                kit.dp(38));
        historyLp.setMargins(kit.dp(6), 0, 0, 0);
        top.addView(binding.historyButton, historyLp);

        binding.settingsButton = kit.actionButton(
                texts.ui("設定", "Settings"),
                kit.surface2);
        binding.settingsButton.setTextSize(11);
        binding.settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                actions.onSettings();
            }
        });
        LinearLayout.LayoutParams settingsLp = new LinearLayout.LayoutParams(
                kit.dp(64),
                kit.dp(38));
        settingsLp.setMargins(kit.dp(6), 0, 0, 0);
        top.addView(binding.settingsButton, settingsLp);

        root.addView(top);
    }

    private void buildCallBar(LinearLayout root, Binding binding) {
        binding.callBar = new LinearLayout(activity);
        binding.callBar.setOrientation(LinearLayout.HORIZONTAL);
        binding.callBar.setGravity(Gravity.CENTER_VERTICAL);
        binding.callBar.setPadding(
                kit.dp(12), kit.dp(8), kit.dp(8), kit.dp(8));
        binding.callBar.setBackground(kit.roundRect(kit.surface, 14));

        binding.callStateText = new TextView(activity);
        binding.callStateText.setTextSize(12);
        binding.callStateText.setTypeface(Typeface.DEFAULT_BOLD);
        binding.callBar.addView(
                binding.callStateText,
                new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f));

        binding.languageButton = kit.actionButton(
                texts.ui("🌐 自動 → 自動", "🌐 Auto → Auto"),
                kit.surface2);
        binding.languageButton.setTextSize(10);
        binding.languageButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                actions.onLanguage();
            }
        });
        LinearLayout.LayoutParams languageLp = new LinearLayout.LayoutParams(
                kit.dp(104),
                kit.dp(38));
        languageLp.setMargins(kit.dp(6), 0, kit.dp(6), 0);
        binding.callBar.addView(binding.languageButton, languageLp);

        binding.audioOutputButton = kit.actionButton(
                texts.ui("🔊 媒體", "🔊 Media"),
                kit.surface2);
        binding.audioOutputButton.setTextSize(10);
        binding.audioOutputButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                actions.onAudioOutput();
            }
        });
        LinearLayout.LayoutParams outputLp = new LinearLayout.LayoutParams(
                kit.dp(82),
                kit.dp(38));
        outputLp.setMargins(0, 0, kit.dp(6), 0);
        binding.callBar.addView(binding.audioOutputButton, outputLp);

        binding.transcriptModeButton = kit.actionButton(
                texts.ui("雙語", "Bilingual"),
                kit.surface2);
        binding.transcriptModeButton.setTextSize(10);
        binding.transcriptModeButton.setOnClickListener(
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        actions.onTranscriptMode();
                    }
                });
        LinearLayout.LayoutParams transcriptLp = new LinearLayout.LayoutParams(
                kit.dp(64),
                kit.dp(38));
        transcriptLp.setMargins(0, 0, kit.dp(6), 0);
        binding.callBar.addView(binding.transcriptModeButton, transcriptLp);

        binding.callToggleButton = kit.actionButton(
                texts.ui("開啟", "Start"),
                kit.surface2);
        binding.callToggleButton.setOnClickListener(
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        actions.onCallToggle();
                    }
                });
        binding.callBar.addView(
                binding.callToggleButton,
                new LinearLayout.LayoutParams(
                        kit.dp(82),
                        kit.dp(38)));

        LinearLayout.LayoutParams callLp = kit.cardLp(10);
        callLp.setMargins(0, kit.dp(12), 0, kit.dp(10));
        root.addView(binding.callBar, callLp);
    }

    private void buildAudienceCard(
            LinearLayout root,
            Binding binding) {
        binding.audienceCard = new LinearLayout(activity);
        binding.audienceCard.setOrientation(LinearLayout.VERTICAL);
        binding.audienceCard.setPadding(
                kit.dp(15), kit.dp(12), kit.dp(15), kit.dp(12));

        binding.audienceTitle = new TextView(activity);
        binding.audienceTitle.setTextSize(14);
        binding.audienceTitle.setTypeface(Typeface.DEFAULT_BOLD);
        binding.audienceCard.addView(binding.audienceTitle);

        binding.audienceDetail = new TextView(activity);
        binding.audienceDetail.setTextSize(12);
        binding.audienceDetail.setLineSpacing(0, 1.15f);
        binding.audienceDetail.setPadding(0, kit.dp(5), 0, 0);
        binding.audienceCard.addView(binding.audienceDetail);

        root.addView(binding.audienceCard, kit.cardLp(10));
    }
}
