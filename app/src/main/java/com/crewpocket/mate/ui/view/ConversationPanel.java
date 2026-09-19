package com.crewpocket.mate.ui.view;

import android.content.Context;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class ConversationPanel {
    public interface Texts {
        String ui(String zh, String en);
    }

    public final TextView externalSectionTitle;
    public final LinearLayout externalTimeline;
    public final ScrollView externalScroll;
    public final TextView privateSectionTitle;
    public final LinearLayout privateTimeline;
    public final ScrollView privateScroll;

    public ConversationPanel(Context context, CrewMateUiKit kit, Texts texts) {
        externalSectionTitle = kit.sectionTitle(
                texts.ui("對外紀錄", "External conversation"));

        externalTimeline = new LinearLayout(context);
        externalTimeline.setOrientation(LinearLayout.VERTICAL);
        externalTimeline.setPadding(
                kit.dp(10), kit.dp(10), kit.dp(10), kit.dp(10));

        externalScroll = new ScrollView(context);
        externalScroll.setFillViewport(true);
        externalScroll.addView(externalTimeline);
        externalScroll.setBackground(kit.roundRect(kit.surface, 18));

        privateSectionTitle = kit.sectionTitle(
                texts.ui("🔒 私人 · 你 ↔ Mate", "🔒 Private · You ↔ Mate"));

        privateTimeline = new LinearLayout(context);
        privateTimeline.setOrientation(LinearLayout.VERTICAL);
        privateTimeline.setPadding(
                kit.dp(10), kit.dp(8), kit.dp(10), kit.dp(8));

        privateScroll = new ScrollView(context);
        privateScroll.setFillViewport(true);
        privateScroll.addView(privateTimeline);
        privateScroll.setBackground(kit.roundRect(kit.surface, 18));
    }

    public void attachTo(LinearLayout root, CrewMateUiKit kit) {
        root.addView(externalSectionTitle);

        LinearLayout.LayoutParams externalLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.2f);
        externalLp.setMargins(0, kit.dp(6), 0, kit.dp(10));
        root.addView(externalScroll, externalLp);

        root.addView(privateSectionTitle);

        LinearLayout.LayoutParams privateLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.45f);
        privateLp.setMargins(0, kit.dp(6), 0, kit.dp(10));
        root.addView(privateScroll, privateLp);
    }
}
