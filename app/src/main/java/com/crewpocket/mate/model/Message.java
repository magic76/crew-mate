package com.crewpocket.mate.model;

import java.util.UUID;

/** One private or in-person spoken turn in a Crew Mate session. */
public final class Message {
    public enum Sender { USER, MATE, OTHER_PERSON, SYSTEM }
    public enum Status { RECEIVED, INFO }

    public final String id;
    public final Sender sender;
    public final String recipient;
    private String content;
    private String translatedText = "";
    private String originalLanguage = "";
    private String translatedLanguage = "";
    public final long timestamp;
    private Status status;

    public Message(Sender sender, String recipient, String content, Status status) {
        this(UUID.randomUUID().toString(), sender, recipient, content, System.currentTimeMillis(), status);
    }

    public Message(String id, Sender sender, String recipient, String content, long timestamp, Status status) {
        this(id, sender, recipient, content, "", "", "", timestamp, status);
    }

    public Message(String id, Sender sender, String recipient, String content,
                   String translatedText, String originalLanguage, String translatedLanguage,
                   long timestamp, Status status) {
        this.id = clean(id, UUID.randomUUID().toString());
        this.sender = sender == null ? Sender.SYSTEM : sender;
        this.recipient = clean(recipient, "");
        this.content = content == null ? "" : content;
        this.translatedText = translatedText == null ? "" : translatedText.trim();
        this.originalLanguage = originalLanguage == null ? "" : originalLanguage.trim();
        this.translatedLanguage = translatedLanguage == null ? "" : translatedLanguage.trim();
        this.timestamp = timestamp;
        this.status = status == null ? Status.INFO : status;
    }

    public synchronized String content() { return content; }
    public synchronized String translatedText() { return translatedText; }
    public synchronized String originalLanguage() { return originalLanguage; }
    public synchronized String translatedLanguage() { return translatedLanguage; }
    public synchronized Status status() { return status; }

    public synchronized void setTranslation(String value, String sourceLanguage, String targetLanguage) {
        translatedText = value == null ? "" : value.trim();
        originalLanguage = sourceLanguage == null ? "" : sourceLanguage.trim();
        translatedLanguage = targetLanguage == null ? "" : targetLanguage.trim();
    }

    private static String clean(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
