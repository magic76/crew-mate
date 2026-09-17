package com.crewpocket.mate.channel;

import java.util.Collections;
import java.util.Locale;

/**
 * Product provider used when Mate talks to a person through the phone's microphone/speaker.
 * It resolves the visible person identity but never sends a remote message. Spoken turns are
 * carried by Gemini Live and projected into CommunicationSession by CrewMateRuntime.
 */
public final class InPersonMessagingBackend implements MessagingBackend {
    @Override
    public ChannelMode channelMode() { return ChannelMode.IN_PERSON; }

    @Override
    public void findContact(String query, FindCallback callback) {
        if (callback == null) return;
        String name = query == null ? "" : query.trim();
        if (name.isEmpty()) {
            callback.onError("Person name is empty");
            return;
        }
        String id = "local-" + name.toLowerCase(Locale.US).replace(' ', '-');
        callback.onFound(new Contact(id, name));
    }

    @Override
    public void getConversation(Contact contact, ConversationCallback callback) {
        if (callback != null) callback.onLoaded(Collections.<RemoteMessage>emptyList());
    }

    @Override
    public void sendMessage(Contact contact, String content, SendCallback callback) {
        if (callback != null) {
            callback.onError("IN_PERSON_USE_LIVE_SPEECH: hand the phone to the other person instead of sending a remote message.");
        }
    }
}
