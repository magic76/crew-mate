package com.crewpocket.mate.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Product-owned communication state. It is intentionally outside agent-core. */
public final class CommunicationSession {
    public enum Status {
        THINKING,
        WAITING_FOR_APPROVAL,
        SENDING,
        WAITING_FOR_REPLY,
        NEEDS_USER_INPUT,
        COMPLETED,
        STOPPED,
        ERROR
    }

    public final String sessionId = UUID.randomUUID().toString();
    private String targetPerson = "";
    private String targetPersonId = "";
    private String goal = "";
    private Status status = Status.THINKING;
    private final List<Message> messages = new ArrayList<Message>();
    private PendingApproval pendingApproval;
    private String pendingUserQuestion = "";

    public synchronized String targetPerson() { return targetPerson; }
    public synchronized String targetPersonId() { return targetPersonId; }
    public synchronized String goal() { return goal; }
    public synchronized Status status() { return status; }
    public synchronized PendingApproval pendingApproval() { return pendingApproval; }
    public synchronized String pendingUserQuestion() { return pendingUserQuestion; }

    public synchronized void setTarget(String id, String displayName) {
        targetPersonId = clean(id);
        targetPerson = clean(displayName);
    }

    public synchronized void setGoal(String value) { goal = clean(value); }
    public synchronized void setStatus(Status value) { if (value != null) status = value; }
    public synchronized void setPendingApproval(PendingApproval value) { pendingApproval = value; }
    public synchronized void setPendingUserQuestion(String value) { pendingUserQuestion = clean(value); }

    public synchronized void addMessage(Message message) {
        if (message == null) return;
        for (Message existing : messages) {
            if (existing.id.equals(message.id)) return;
        }
        messages.add(message);
    }

    public synchronized Message findMessage(String id) {
        if (id == null) return null;
        for (Message message : messages) {
            if (id.equals(message.id)) return message;
        }
        return null;
    }

    public synchronized Message latestDraft() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message.sender == Message.Sender.MATE
                    && (message.status() == Message.Status.DRAFT
                    || message.status() == Message.Status.PENDING_APPROVAL)) {
                return message;
            }
        }
        return null;
    }

    public synchronized List<Message> messages() {
        return Collections.unmodifiableList(new ArrayList<Message>(messages));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
