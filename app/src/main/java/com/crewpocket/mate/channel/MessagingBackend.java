package com.crewpocket.mate.channel;

import java.util.List;

/** Provider boundary. Telegram/LINE/email adapters can implement this later. */
public interface MessagingBackend {
    final class Contact {
        public final String id;
        public final String displayName;

        public Contact(String id, String displayName) {
            this.id = id == null ? "" : id;
            this.displayName = displayName == null ? "" : displayName;
        }
    }

    final class RemoteMessage {
        public final String id;
        public final String sender;
        public final String content;
        public final long timestamp;
        public final boolean outgoing;

        public RemoteMessage(String id, String sender, String content, long timestamp, boolean outgoing) {
            this.id = id == null ? "" : id;
            this.sender = sender == null ? "" : sender;
            this.content = content == null ? "" : content;
            this.timestamp = timestamp;
            this.outgoing = outgoing;
        }
    }

    interface FindCallback {
        void onFound(Contact contact);
        void onError(String message);
    }

    interface ConversationCallback {
        void onLoaded(List<RemoteMessage> messages);
        void onError(String message);
    }

    interface SendCallback {
        void onDelivered(String providerMessageId);
        void onReply(RemoteMessage reply);
        void onError(String message);
    }

    void findContact(String query, FindCallback callback);
    void getConversation(Contact contact, ConversationCallback callback);
    void sendMessage(Contact contact, String content, SendCallback callback);
}
