package com.crewpocket.mate.agent;

import com.crewpocket.mate.model.CommunicationSession;
import com.magic76.crew.agent.AgentEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Privacy-safe trace: metadata only, never private transcript or message bodies. */
public final class AgentTraceRecorder {
    public static final class TraceEntry {
        public final String sessionId;
        public final String eventType;
        public final String toolName;
        public final String toolCallId;
        public final Boolean success;
        public final long durationMs;
        public final long timestampMs;

        TraceEntry(String sessionId, String eventType, String toolName, String toolCallId,
                   Boolean success, long durationMs, long timestampMs) {
            this.sessionId = sessionId;
            this.eventType = eventType;
            this.toolName = toolName;
            this.toolCallId = toolCallId;
            this.success = success;
            this.durationMs = durationMs;
            this.timestampMs = timestampMs;
        }
    }

    private final List<TraceEntry> entries = new ArrayList<TraceEntry>();
    private final Map<String, Long> toolStartedAt = new LinkedHashMap<String, Long>();

    public synchronized void record(CommunicationSession session, AgentEvent event) {
        if (session == null || event == null) return;
        String toolName = event.toolCall() == null ? "" : event.toolCall().name();
        String callId = event.toolCall() == null ? "" : event.toolCall().id();
        Boolean success = null;
        long duration = -1L;

        if (event.type() == AgentEvent.Type.TOOL_REQUESTED && !callId.isEmpty()) {
            toolStartedAt.put(callId, event.timestampMs());
        }
        if ((event.type() == AgentEvent.Type.TOOL_COMPLETED || event.type() == AgentEvent.Type.TOOL_FAILED)
                && !callId.isEmpty()) {
            Long started = toolStartedAt.remove(callId);
            if (started != null) duration = Math.max(0L, event.timestampMs() - started);
            success = event.type() == AgentEvent.Type.TOOL_COMPLETED;
        }

        switch (event.type()) {
            case STARTED:
            case TOOL_REQUESTED:
            case TOOL_COMPLETED:
            case TOOL_FAILED:
            case INTERRUPTED:
            case TURN_COMPLETED:
            case STOPPED:
            case ERROR:
                entries.add(new TraceEntry(session.sessionId, event.type().name(), toolName, callId,
                        success, duration, event.timestampMs()));
                break;
            default:
                // USER_TEXT / MODEL_TEXT / audio contents are intentionally excluded.
                break;
        }
    }

    public synchronized List<TraceEntry> snapshot() {
        return Collections.unmodifiableList(new ArrayList<TraceEntry>(entries));
    }

    public synchronized String serialize() {
        StringBuilder out = new StringBuilder();
        for (TraceEntry entry : entries) {
            out.append(entry.timestampMs).append('|')
                    .append(entry.sessionId).append('|')
                    .append(entry.eventType).append('|')
                    .append(entry.toolName).append('|')
                    .append(entry.toolCallId).append('|')
                    .append(entry.success == null ? "" : entry.success).append('|')
                    .append(entry.durationMs).append('\n');
        }
        return out.toString();
    }
}
