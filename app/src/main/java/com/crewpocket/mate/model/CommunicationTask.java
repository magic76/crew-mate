package com.crewpocket.mate.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class CommunicationTask {
    public enum State {
        PLANNING,
        COMMUNICATING,
        WAITING,
        NEEDS_USER,
        COMPLETED,
        PAUSED
    }

    public final String id = UUID.randomUUID().toString();
    public String contact;
    public String goal;
    public final List<String> privateContext = new ArrayList<String>();
    public final List<Message> externalMessages = new ArrayList<Message>();
    public State state = State.PLANNING;
    public String pendingDraft = "";
    public String summary = "";

    public CommunicationTask(String contact, String goal) {
        this.contact = clean(contact, "Unknown contact");
        this.goal = clean(goal, "Communication task");
    }

    public synchronized void addPrivateContext(String value) {
        if (value != null && !value.trim().isEmpty()) privateContext.add(value.trim());
    }

    public synchronized void addExternalMessage(Message message) {
        if (message != null) externalMessages.add(message);
    }

    public synchronized List<Message> snapshotMessages() {
        return Collections.unmodifiableList(new ArrayList<Message>(externalMessages));
    }

    private static String clean(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
