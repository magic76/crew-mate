package com.crewpocket.mate.ui.view;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class TaskSetupPanel extends LinearLayout {
    public interface Texts {
        String ui(String zh, String en);
    }

    public final TextView label;
    public final Button voiceButton;
    public final EditText input;
    public final Button inputButton;

    public TaskSetupPanel(
            Context context,
            CrewMateUiKit kit,
            Texts texts,
            View.OnClickListener voiceClick,
            View.OnClickListener submitClick) {
        super(context);
        setOrientation(VERTICAL);
        setPadding(kit.dp(14), kit.dp(12), kit.dp(14), kit.dp(12));
        setBackground(kit.roundRect(kit.surface, 18));

        label = new TextView(context);
        label.setText(texts.ui("先告訴 Mate 你想做什麼", "Tell Mate what you need first"));
        label.setTextColor(kit.text);
        label.setTextSize(16);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        addView(label);

        voiceButton = kit.actionButton(
                texts.ui("🎙  口頭交代", "🎙  Speak to Mate"),
                kit.accent);
        voiceButton.setTextSize(14);
        voiceButton.setOnClickListener(voiceClick);
        LinearLayout.LayoutParams voiceLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                kit.dp(54));
        voiceLp.setMargins(0, kit.dp(12), 0, kit.dp(12));
        addView(voiceButton, voiceLp);

        input = new EditText(context);
        input.setHint(texts.ui(
                "或直接輸入，例如：幫我問櫃台能不能延後退房，超過 500 泰銖先問我",
                "Or type it, e.g. ask the front desk for late checkout; ask me first if it costs over 500 THB"));
        input.setTextColor(kit.text);
        input.setHintTextColor(kit.muted);
        input.setTextSize(13);
        input.setSingleLine(false);
        input.setMinLines(2);
        input.setMaxLines(4);
        input.setPadding(kit.dp(10), kit.dp(8), kit.dp(10), kit.dp(8));
        input.setBackground(kit.roundRect(kit.surface2, 12));
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        inputLp.setMargins(0, kit.dp(10), 0, kit.dp(8));
        addView(input, inputLp);

        inputButton = kit.actionButton(
                texts.ui("送出文字", "Send text"),
                kit.accentSurface);
        inputButton.setOnClickListener(submitClick);
        addView(inputButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                kit.dp(44)));
    }
}
