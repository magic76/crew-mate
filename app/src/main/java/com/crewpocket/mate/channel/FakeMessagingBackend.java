package com.crewpocket.mate.channel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Deterministic fake provider used by the first end-to-end Crew Mate flow and JVM tests. */
public final class FakeMessagingBackend implements MessagingBackend {
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final long replyDelayMs;
    private final List<RemoteMessage> history = new ArrayList<RemoteMessage>();
    private int sendCount;

    public FakeMessagingBackend() { this(900L); }
    public FakeMessagingBackend(long replyDelayMs) { this.replyDelayMs = Math.max(0L, replyDelayMs); }

    @Override
    public void findContact(final String query, final FindCallback callback) {
        if (callback == null) return;
        String name = query == null ? "" : query.trim();
        if (name.isEmpty()) {
            callback.onError("Contact name is empty");
            return;
        }
        callback.onFound(new Contact(name.toLowerCase(Locale.US).replace(' ', '-'), name));
    }

    @Override
    public synchronized void getConversation(Contact contact, ConversationCallback callback) {
        if (callback != null) callback.onLoaded(Collections.unmodifiableList(new ArrayList<RemoteMessage>(history)));
    }

    @Override
    public void sendMessage(final Contact contact, final String content, final SendCallback callback) {
        if (callback == null) return;
        final String outgoingId = UUID.randomUUID().toString();
        synchronized (this) {
            sendCount++;
            history.add(new RemoteMessage(outgoingId, "Mate", content, System.currentTimeMillis(), true));
        }
        callback.onDelivered(outgoingId);

        executor.schedule(new Runnable() {
            @Override public void run() {
                String replyText = buildReply(content);
                RemoteMessage reply = new RemoteMessage(UUID.randomUUID().toString(),
                        contact == null ? "Other person" : contact.displayName,
                        replyText, System.currentTimeMillis(), false);
                synchronized (FakeMessagingBackend.this) { history.add(reply); }
                callback.onReply(reply);
            }
        }, replyDelayMs, TimeUnit.MILLISECONDS);
    }

    public synchronized int sendCount() { return sendCount; }

    public void shutdown() { executor.shutdownNow(); }

    private String buildReply(String content) {
        String value = content == null ? "" : content.toLowerCase(Locale.US);
        if (value.contains("dinner") || value.contains("吃飯") || value.contains("restaurant")) {
            return "明天 7 點可以。";
        }
        if (value.contains("meeting") || value.contains("meet") || value.contains("會議")) {
            return "4:30 PM works for me. Should we meet at the office?";
        }
        if (value.contains("checkout") || value.contains("late check")) {
            return "Late checkout until 2 PM is available for $30. Would you like to confirm it?";
        }
        return "That works for me. Could you confirm the remaining details?";
    }
}
