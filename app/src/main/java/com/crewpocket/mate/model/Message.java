package com.crewpocket.mate.model;

import java.util.UUID;

/** One first-class message in a Crew Mate communication session. */
public final class Message {
    public enum Sender { USER, MATE, OTHER_PERSON, SYSTEM }

    public enum Status {
        DRAFT,
        PENDING_APPROVAL,
        SENDING,
        SENT,
        DELIVERED,
        RECEIVED,
        INFO,
        CANCELLED,
        FAILED
    }

    public final String id;
    public final Sender sender;
    public final String recipient;
    private String content;
    public final long timestamp;
    private Status status;

    public Message(Sender sender, String recipient, String content, Status status) {
        this(UUID.randomUUID().toString(), sender, recipient, content, System.currentTimeMillis(), status);
    }

    public Message(String id, Sender sender, String recipient, String content, long timestamp, Status status) {
        this.id = clean(id, UUID.randomUUID().toString());
        this.sender = sender == null ? Sender.SYSTEM : sender;
        this.recipient = clean(recipient, "");
        this.content = content == null ? "" : content;
        this.timestamp = timestamp;
        this.status = status == null ? Status.INFO : status;
    }

    public synchronized String content() { return content; }
    public synchronized Status status() { return status; }

    public synchronized void updateContent(String value) {
        content = value == null ? "" : value;
    }

    public synchronized void updateStatus(Status value) {
        if (value != null) status = value;
    }

    private static String clean(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
