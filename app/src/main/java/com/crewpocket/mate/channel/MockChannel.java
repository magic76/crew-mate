package com.crewpocket.mate.channel;

import android.os.Handler;
import android.os.Looper;

import com.crewpocket.mate.model.CommunicationTask;

import java.util.Locale;

public class MockChannel implements ChannelAdapter {
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void send(final CommunicationTask task, final String text, final Callback callback) {
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                callback.onDelivered();
                callback.onReply(buildReply(task, text));
            }
        }, 1400L);
    }

    private String buildReply(CommunicationTask task, String text) {
        String combined = ((task == null ? "" : task.goal) + " " + (text == null ? "" : text)).toLowerCase(Locale.US);
        if (combined.contains("checkout") || combined.contains("late check")) {
            return "Late checkout until 2 PM is available for $30. Would you like me to confirm it?";
        }
        if (combined.contains("dinner") || combined.contains("lunch") || combined.contains("eat") || combined.contains("restaurant")) {
            return "6 PM works for me. Would you prefer Xinyi or Da'an?";
        }
        if (combined.contains("meeting") || combined.contains("tomorrow") || combined.contains("schedule") || combined.contains("meet")) {
            return "4:30 PM works for me. Should we meet at the office?";
        }
        if (combined.contains("price") || combined.contains("quote") || combined.contains("cost")) {
            return "I can do it for $300. Let me know if you want to proceed.";
        }
        return "That works for me. Could you confirm the remaining details?";
    }
}
