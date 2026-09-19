package com.crewpocket.mate.ui.view;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class CrewMateUiKit {
    private final Context context;

    public final int bg;
    public final int surface;
    public final int surface2;
    public final int text;
    public final int muted;
    public final int accent;
    public final int accentSurface;
    public final int direct;

    public CrewMateUiKit(
            Context context,
            int bg,
            int surface,
            int surface2,
            int text,
            int muted,
            int accent,
            int accentSurface,
            int direct) {
        this.context = context;
        this.bg = bg;
        this.surface = surface;
        this.surface2 = surface2;
        this.text = text;
        this.muted = muted;
        this.accent = accent;
        this.accentSurface = accentSurface;
        this.direct = direct;
    }

    public int dp(float value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    public GradientDrawable roundRect(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    public Button actionButton(String label, int color) {
        Button button = new Button(context);
        button.setText(label);
        button.setTextSize(11);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setBackground(roundRect(color, 11));
        return button;
    }

    public TextView sectionTitle(String value) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(12);
        view.setTextColor(muted);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    public TextView cardText(int size) {
        TextView view = new TextView(context);
        view.setTextColor(text);
        view.setTextSize(size);
        view.setLineSpacing(0, 1.2f);
        view.setPadding(dp(16), dp(14), dp(16), dp(14));
        return view;
    }

    public LinearLayout.LayoutParams cardLp(int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(bottom));
        return lp;
    }
}
