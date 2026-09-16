package com.crewpocket.mate.model;

public class Message {
    public enum Sender { MATE, CONTACT }

    public final Sender sender;
    public final String text;
    public final long timestamp;

    public Message(Sender sender, String text) {
        this(sender, text, System.currentTimeMillis());
    }

    public Message(Sender sender, String text, long timestamp) {
        this.sender = sender;
        this.text = text == null ? "" : text;
        this.timestamp = timestamp;
    }
}
