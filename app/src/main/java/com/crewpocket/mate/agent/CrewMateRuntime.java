package com.crewpocket.mate.agent;

import com.crewpocket.mate.channel.MessagingBackend;
import com.crewpocket.mate.model.CommunicationSession;
import com.crewpocket.mate.model.Message;
import com.crewpocket.mate.model.PendingApproval;
import com.crewpocket.mate.voice.TurnTextAccumulator;
import com.magic76.crew.agent.AgentEvent;
import com.magic76.crew.agent.AgentHarness;
import com.magic76.crew.agent.ModelSession;

import java.util.List;

/**
 * Crew Mate's thin product runtime around the shared AgentHarness.
 * It owns communication state, approval, provider callbacks and UI projection only.
 */
public final class CrewMateRuntime implements AgentHarness.Listener, CrewMateToolRegistry.Listener {
    public interface Listener {
        void onSessionChanged(CommunicationSession session);
        void onApprovalRequired(CommunicationSession session, PendingApproval approval);
        void onRuntimeStatus(String status);
    }

    private final CommunicationSession session;
    private final AgentTraceRecorder traceRecorder = new AgentTraceRecorder();
    private final TurnTextAccumulator modelTurn = new TurnTextAccumulator();
    private final CrewMateToolRegistry tools;
    private final AgentHarness harness;
    private final Listener listener;

    public CrewMateRuntime(ModelSession modelSession, MessagingBackend backend, Listener listener) {
        this(new CommunicationSession(), modelSession, backend, listener);
    }

    public CrewMateRuntime(CommunicationSession session, ModelSession modelSession,
                           MessagingBackend backend, Listener listener) {
        if (session == null) throw new IllegalArgumentException("session is null");
        this.session = session;
        this.listener = listener;
        tools = new CrewMateToolRegistry(session, backend, this);
        harness = new AgentHarness(new CrewMateAgentSpec(), modelSession, tools.registry(), this);
    }

    public CommunicationSession session() { return session; }
    public List<AgentTraceRecorder.TraceEntry> trace() { return traceRecorder.snapshot(); }
    public String serializedTrace() { return traceRecorder.serialize(); }

    public void start() { harness.start(); }
    public void close() { modelTurn.clear(); harness.close(); }
    public void interrupt() { harness.interrupt(); }

    /** Used for typed/private control paths. Live microphone audio stays inside the Gemini adapter. */
    public void submitPrivateText(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) return;
        recordUserTranscript(value);
        harness.submitText(value);
    }

    /** Input transcription is display/state only; Gemini already received the corresponding audio. */
    public void recordUserTranscript(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) return;
        session.addMessage(new Message(Message.Sender.USER, "MATE", value, Message.Status.RECEIVED));
        if (session.status() == CommunicationSession.Status.NEEDS_USER_INPUT) {
            session.setPendingUserQuestion("");
            session.setStatus(CommunicationSession.Status.THINKING);
        }
        notifyChanged();
    }

    public boolean approvePending(String editedContent) { return tools.approvePending(editedContent); }
    public boolean cancelPending() { return tools.cancelPending(); }

    @Override
    public void onAgentEvent(AgentEvent event) {
        traceRecorder.record(session, event);
        if (event == null) return;
        switch (event.type()) {
            case MODEL_TEXT:
                modelTurn.append(event.text());
                break;
            case TOOL_REQUESTED:
                // Some providers request a tool before emitting TURN_COMPLETED. Preserve any
                // already-spoken text as one message instead of leaving it invisible.
                commitModelTurn();
                break;
            case STARTED:
                emitStatus("Thinking");
                break;
            case INTERRUPTED:
                // Interrupted model speech is intentionally not persisted as a completed message.
                modelTurn.clear();
                emitStatus("Interrupted");
                break;
            case TURN_COMPLETED:
                commitModelTurn();
                emitStatus(displayStatus(session.status()));
                break;
            case STOPPED:
                modelTurn.clear();
                session.setStatus(CommunicationSession.Status.STOPPED);
                notifyChanged();
                emitStatus("Stopped");
                break;
            case ERROR:
                modelTurn.clear();
                session.setStatus(CommunicationSession.Status.ERROR);
                notifyChanged();
                emitStatus("Error");
                break;
            default:
                break;
        }
    }

    private void commitModelTurn() {
        String completed = modelTurn.take();
        if (completed.isEmpty()) return;
        session.addMessage(new Message(Message.Sender.MATE, "USER", completed, Message.Status.INFO));
        notifyChanged();
    }

    @Override public void onSessionChanged(CommunicationSession changed) { notifyChanged(); }

    @Override
    public void onApprovalRequired(CommunicationSession changed, PendingApproval approval) {
        notifyChanged();
        emitStatus("Waiting for approval");
        if (listener != null) listener.onApprovalRequired(changed, approval);
    }

    @Override
    public void onExternalReply(CommunicationSession changed, Message message) {
        notifyChanged();
        emitStatus("Thinking");
        String person = changed.targetPerson().isEmpty() ? "OTHER_PERSON" : changed.targetPerson();
        harness.submitText("EXTERNAL_MESSAGE from " + person + ": " + message.content()
                + "\nContinue only within the user's stated goal. If a new decision is needed, call request_user_input.");
    }

    @Override
    public void onUserInputRequested(CommunicationSession changed, String question, String reason) {
        notifyChanged();
        emitStatus("Needs your input");
    }

    private void notifyChanged() {
        if (listener != null) listener.onSessionChanged(session);
    }

    private void emitStatus(String status) {
        if (listener != null) listener.onRuntimeStatus(status);
    }

    public static String displayStatus(CommunicationSession.Status status) {
        if (status == null) return "Thinking";
        switch (status) {
            case WAITING_FOR_APPROVAL: return "Waiting for approval";
            case SENDING: return "Sending";
            case WAITING_FOR_REPLY: return "Waiting for reply";
            case NEEDS_USER_INPUT: return "Needs your input";
            case COMPLETED: return "Completed";
            case STOPPED: return "Stopped";
            case ERROR: return "Error";
            default: return "Thinking";
        }
    }
}
