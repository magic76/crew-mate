package com.crewpocket.mate.agent;

import com.crewpocket.mate.model.CommunicationSession;
import com.crewpocket.mate.model.Message;
import com.crewpocket.mate.model.SpeechAudience;
import com.crewpocket.mate.voice.TurnTextAccumulator;
import com.magic76.crew.agent.AgentEvent;
import com.magic76.crew.agent.AgentHarness;
import com.magic76.crew.agent.ModelSession;

import java.util.List;

/** Product runtime for Crew Mate's in-person Gemini Live conversation. */
public final class CrewMateRuntime implements AgentHarness.Listener, CrewMateToolRegistry.Listener {
    public interface Listener {
        void onSessionChanged(CommunicationSession session);
        void onRuntimeStatus(String status);
    }

    private final CommunicationSession session;
    private final AgentTraceRecorder traceRecorder = new AgentTraceRecorder();
    private final TurnTextAccumulator modelTurn = new TurnTextAccumulator();
    private final AgentHarness harness;
    private final Listener listener;
    private volatile boolean closed;
    private volatile SpeechAudience speechAudience = SpeechAudience.MATE_HANDLING;
    private volatile boolean externalSpeechObserved;

    public CrewMateRuntime(ModelSession modelSession, Listener listener) {
        this(new CommunicationSession(), modelSession, listener);
    }

    public CrewMateRuntime(CommunicationSession session, ModelSession modelSession, Listener listener) {
        if (session == null) throw new IllegalArgumentException("session is null");
        this.session = session;
        this.listener = listener;
        CrewMateToolRegistry tools = new CrewMateToolRegistry(session, this);
        harness = new AgentHarness(new CrewMateAgentSpec(), modelSession, tools.registry(), this);
    }

    public CommunicationSession session() { return session; }
    public List<AgentTraceRecorder.TraceEntry> trace() { return traceRecorder.snapshot(); }
    public String serializedTrace() { return traceRecorder.serialize(); }
    public SpeechAudience speechAudience() { return speechAudience; }

    public synchronized void setSpeechAudience(SpeechAudience audience) {
        SpeechAudience next = audience == null ? SpeechAudience.MATE_HANDLING : audience;
        if (speechAudience == next) return;
        commitModelTurn();
        speechAudience = next;
        externalSpeechObserved = false;
    }

    public void start() {
        closed = false;
        harness.start();
    }

    public void close() {
        if (closed) return;
        closed = true;
        modelTurn.clear();
        harness.close();
    }

    public void interrupt() {
        if (!closed) harness.interrupt();
    }

    public void submitPrivateText(String text) {
        if (closed || session.userDirectControl()) return;
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) return;
        session.addMessage(new Message(Message.Sender.USER, "MATE", value, Message.Status.RECEIVED));
        session.setConsensusReady(false);
        if (session.status() == CommunicationSession.Status.NEEDS_USER_INPUT) {
            session.setPendingUserQuestion("");
            session.setStatus(CommunicationSession.Status.THINKING);
        }
        notifyChanged();
        harness.submitText(value);
    }

    public void releaseUserDirectControl() {
        if (closed) return;
        session.setUserDirectControl(false);
        notifyChanged();
    }

    public void recordUserTranscript(String text) {
        if (closed || session.userDirectControl() || speechAudience != SpeechAudience.PRIVATE_TO_MATE) return;
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) return;
        session.addMessage(new Message(Message.Sender.USER, "MATE", value, Message.Status.RECEIVED));
        session.setConsensusReady(false);
        if (session.status() == CommunicationSession.Status.NEEDS_USER_INPUT) {
            session.setPendingUserQuestion("");
            session.setStatus(CommunicationSession.Status.THINKING);
        }
        notifyChanged();
    }

    public void recordExternalSpeechTranscript(String text) {
        if (closed || session.userDirectControl() || speechAudience != SpeechAudience.EXTERNAL_WITH_MATE) return;
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) return;
        session.addMessage(new Message(Message.Sender.OTHER_PERSON, "MATE", value, Message.Status.RECEIVED));
        externalSpeechObserved = true;
        session.setStatus(CommunicationSession.Status.THINKING);
        notifyChanged();
    }

    @Override
    public void onAgentEvent(AgentEvent event) {
        traceRecorder.record(session, event);
        if (event == null) return;
        switch (event.type()) {
            case MODEL_TEXT:
                if (!closed && (speechAudience == SpeechAudience.PRIVATE_TO_MATE
                        || speechAudience == SpeechAudience.EXTERNAL_WITH_MATE)) {
                    modelTurn.append(event.text());
                }
                break;
            case TOOL_REQUESTED:
                if (!closed) commitModelTurn();
                break;
            case STARTED:
                if (!closed) emitStatus("Thinking");
                break;
            case INTERRUPTED:
                modelTurn.clear();
                if (!closed) emitStatus("Interrupted");
                break;
            case TURN_COMPLETED:
                if (!closed) {
                    commitModelTurn();
                    emitStatus(displayStatus(session.status()));
                }
                break;
            case STOPPED:
                modelTurn.clear();
                if (!preserveProductStateOnRuntimeStop(session.status())) {
                    session.setStatus(CommunicationSession.Status.STOPPED);
                }
                notifyChanged();
                emitStatus(displayStatus(session.status()));
                break;
            case ERROR:
                modelTurn.clear();
                if (session.status() != CommunicationSession.Status.COMPLETED) {
                    session.setStatus(CommunicationSession.Status.ERROR);
                }
                notifyChanged();
                emitStatus(displayStatus(session.status()));
                break;
            default:
                break;
        }
    }

    private boolean preserveProductStateOnRuntimeStop(CommunicationSession.Status status) {
        return status == CommunicationSession.Status.NEEDS_USER_INPUT
                || status == CommunicationSession.Status.COMPLETED;
    }

    private void commitModelTurn() {
        String completed = modelTurn.take();
        if (completed.isEmpty()) return;
        if (speechAudience == SpeechAudience.EXTERNAL_WITH_MATE) {
            if (!externalSpeechObserved) return;
            String recipient = session.targetPerson().isEmpty() ? "OTHER_PERSON" : session.targetPerson();
            session.addMessage(new Message(Message.Sender.MATE, recipient, completed, Message.Status.INFO));
            externalSpeechObserved = false;
            notifyChanged();
        } else if (speechAudience == SpeechAudience.PRIVATE_TO_MATE) {
            session.addMessage(new Message(Message.Sender.MATE, "USER", completed, Message.Status.INFO));
            notifyChanged();
        }
    }

    @Override
    public void onSessionChanged(CommunicationSession changed) {
        if (!closed) notifyChanged();
    }

    @Override
    public void onUserInputRequested(CommunicationSession changed, String question, String reason) {
        if (closed) return;
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
            case NEEDS_USER_INPUT: return "Needs your input";
            case COMPLETED: return "Completed";
            case STOPPED: return "Paused";
            case ERROR: return "Error";
            default: return "Thinking";
        }
    }
}
