package com.crewpocket.mate.model;

import java.util.UUID;

/** User-visible authorization boundary for one exact outbound send. */
public final class PendingApproval {
    public enum State { WAITING, APPROVED, CANCELLED, SENT, FAILED }

    public final String id = UUID.randomUUID().toString();
    public final String toolCallId;
    public final String draftMessageId;
    public final String reason;
    private String content;
    private State state = State.WAITING;

    public PendingApproval(String toolCallId, String draftMessageId, String content, String reason) {
        this.toolCallId = toolCallId == null ? "" : toolCallId;
        this.draftMessageId = draftMessageId == null ? "" : draftMessageId;
        this.content = content == null ? "" : content;
        this.reason = reason == null ? "" : reason;
    }

    public synchronized String content() { return content; }
    public synchronized State state() { return state; }

    public synchronized void updateContent(String value) {
        if (state == State.WAITING) content = value == null ? "" : value;
    }

    public synchronized void updateState(State value) {
        if (value != null) state = value;
    }
}
