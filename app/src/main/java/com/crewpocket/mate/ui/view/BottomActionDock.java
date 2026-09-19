package com.crewpocket.mate.ui.view;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

public final class BottomActionDock extends LinearLayout {
    public interface Texts {
        String ui(String zh, String en);
    }

    public final Button primaryButton;
    public final Button directButton;
    public final Button completeButton;
    public final Button moreButton;

    public BottomActionDock(
            Context context,
            CrewMateUiKit kit,
            Texts texts,
            View.OnClickListener primaryClick,
            View.OnClickListener secondaryClick,
            View.OnClickListener completeClick,
            View.OnClickListener moreClick) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.BOTTOM | Gravity.CENTER_VERTICAL);
        setClipChildren(false);
        setClipToPadding(false);
        setPadding(0, kit.dp(4), 0, kit.dp(8));

        primaryButton = kit.actionButton(
                texts.ui("🔒 交代給 Mate", "🔒 Brief Mate"),
                kit.accent);
        primaryButton.setTextSize(13);
        primaryButton.setOnClickListener(primaryClick);
        addView(primaryButton, new LinearLayout.LayoutParams(
                0, kit.dp(52), 1.45f));

        directButton = kit.actionButton(
                texts.ui("我要自己說", "I'll speak myself"),
                kit.direct);
        directButton.setOnClickListener(secondaryClick);
        LinearLayout.LayoutParams directLp = new LinearLayout.LayoutParams(
                0, kit.dp(52), 1f);
        directLp.setMargins(kit.dp(8), 0, 0, 0);
        addView(directButton, directLp);

        completeButton = kit.actionButton(
                texts.ui("完成任務", "Complete task"),
                Color.rgb(5, 150, 105));
        completeButton.setTextSize(11);
        completeButton.setOnClickListener(completeClick);
        LinearLayout.LayoutParams endLp = new LinearLayout.LayoutParams(
                0, kit.dp(52), 0.92f);
        endLp.setMargins(kit.dp(8), 0, 0, 0);
        addView(completeButton, endLp);

        moreButton = kit.actionButton("⋯", kit.surface2);
        moreButton.setTextSize(16);
        moreButton.setOnClickListener(moreClick);
        LinearLayout.LayoutParams moreLp = new LinearLayout.LayoutParams(
                kit.dp(48), kit.dp(52));
        moreLp.setMargins(kit.dp(8), 0, 0, 0);
        addView(moreButton, moreLp);
    }

    public static View createSpacer(Context context) {
        View spacer = new View(context);
        spacer.setVisibility(View.GONE);
        return spacer;
    }

    public static LinearLayout.LayoutParams spacerLayoutParams() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
    }
}
