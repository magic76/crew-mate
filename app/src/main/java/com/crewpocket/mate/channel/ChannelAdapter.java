package com.crewpocket.mate.channel;

import com.crewpocket.mate.model.CommunicationTask;

public interface ChannelAdapter {
    interface Callback {
        void onDelivered();
        void onReply(String text);
        void onError(String message);
    }

    void send(CommunicationTask task, String text, Callback callback);
}
