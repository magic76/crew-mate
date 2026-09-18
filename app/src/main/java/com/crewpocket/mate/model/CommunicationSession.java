package com.crewpocket.mate.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Product-owned state for one in-person Crew Mate conversation. */
public final class CommunicationSession {
    public enum Status {
        THINKING,
        NEEDS_USER_INPUT,
        COMPLETED,
        STOPPED,
        ERROR
    }

    public final String sessionId;
    private String targetPerson = "";
    private String targetPersonId = "";
    private String goal = "";
    private String outcomeSummary = "";
    private boolean userDirectControl;
    private Status status = Status.THINKING;
    private final List<Message> messages = new ArrayList<Message>();
    private String pendingUserQuestion = "";
    private long updatedAt = System.currentTimeMillis();

    public CommunicationSession() { this(UUID.randomUUID().toString()); }

    public CommunicationSession(String sessionId) {
        this.sessionId = clean(sessionId).isEmpty() ? UUID.randomUUID().toString() : clean(sessionId);
    }

    public synchronized String targetPerson() { return targetPerson; }
    public synchronized String targetPersonId() { return targetPersonId; }
    public synchronized String goal() { return goal; }
    public synchronized String outcomeSummary() { return outcomeSummary; }
    public synchronized boolean userDirectControl() { return userDirectControl; }
    public synchronized Status status() { return status; }
    public synchronized String pendingUserQuestion() { return pendingUserQuestion; }
    public synchronized long updatedAt() { return updatedAt; }

    public synchronized void setTarget(String id, String displayName) {
        targetPersonId = clean(id);
        targetPerson = clean(displayName);
        touch();
    }

    public synchronized void setGoal(String value) { goal = clean(value); touch(); }
    public synchronized void setOutcomeSummary(String value) { outcomeSummary = clean(value); touch(); }
    public synchronized void setUserDirectControl(boolean value) { userDirectControl = value; touch(); }

    public synchronized void setStatus(Status value) {
        if (value != null) { status = value; touch(); }
    }

    public synchronized void setPendingUserQuestion(String value) { pendingUserQuestion = clean(value); touch(); }
    public synchronized void setUpdatedAtForRestore(long value) { if (value > 0) updatedAt = value; }

    public synchronized void addMessage(Message message) {
        if (message == null) return;
        for (Message existing : messages) if (existing.id.equals(message.id)) return;
        messages.add(message);
        updatedAt = Math.max(System.currentTimeMillis(), message.timestamp);
    }

    public synchronized List<Message> messages() {
        return Collections.unmodifiableList(new ArrayList<Message>(messages));
    }

    private void touch() { updatedAt = System.currentTimeMillis(); }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
