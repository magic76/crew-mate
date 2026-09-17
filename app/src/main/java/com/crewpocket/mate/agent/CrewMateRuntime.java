package com.crewpocket.mate.agent;

import com.crewpocket.mate.channel.MessagingBackend;
import com.crewpocket.mate.model.CommunicationSession;
import com.crewpocket.mate.model.Message;
import com.crewpocket.mate.model.PendingApproval;
import com.crewpocket.mate.model.SpeechAudience;
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
    private volatile boolean closed;
    private volatile SpeechAudience speechAudience = SpeechAudience.MATE_HANDLING;

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
    public SpeechAudience speechAudience() { return speechAudience; }

    public void setSpeechAudience(SpeechAudience audience) {
        speechAudience = audience == null ? SpeechAudience.MATE_HANDLING : audience;
    }

    public void start() {
        closed = false;
        harness.start();
    }

    public void close() {
        if (closed) return;
        closed = true;
        modelTurn.clear();
        tools.cancelPending();
        harness.close();
    }

    public void interrupt() {
        if (!closed) harness.interrupt();
    }

    /** Used for typed/private control paths. Live microphone audio stays inside the Gemini adapter. */
    public void submitPrivateText(String text) {
        if (closed || session.userDirectControl()) return;
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) return;
        recordUserTranscript(value);
        harness.submitText(value);
    }

    /** Rehydrates model context after a background reply without creating a fake private user message. */
    public void resumePersistedTask() {
        if (closed || session.userDirectControl()) return;
        StringBuilder context = new StringBuilder();
        context.append("RESUME_DELEGATED_COMMUNICATION_TASK\n")
                .append("Target: ").append(session.targetPerson()).append("\n")
                .append("Goal: ").append(session.goal()).append("\n")
                .append("Delegated: ").append(session.delegationAuthorized()).append("\n")
                .append("Recent context:\n");
        List<Message> messages = session.messages();
        int start = Math.max(0, messages.size() - 16);
        for (int i = start; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (message.sender == Message.Sender.USER) {
                context.append("PRIVATE_USER_BRIEF: ").append(message.content()).append("\n");
            } else if (message.sender == Message.Sender.OTHER_PERSON) {
                context.append("OTHER_PERSON: ").append(message.content()).append("\n");
            } else if (message.sender == Message.Sender.MATE && !"USER".equals(message.recipient)) {
                context.append("MATE_TO_OTHER: ").append(message.content()).append("\n");
            }
        }
        context.append("Continue the delegated task yourself if the next step is routine and within scope. "
                + "If a new consequential decision is required, call request_user_input. "
                + "If the goal is achieved, call complete_task. Do not resend any message already present in the timeline.");
        session.setStatus(CommunicationSession.Status.THINKING);
        notifyChanged();
        harness.submitText(context.toString());
    }

    /** Called only after the user explicitly presses 'hand back to Mate'. */
    public void resumeAfterUserTakeover() {
        if (closed) return;
        session.setUserDirectControl(false);
        notifyChanged();
        resumePersistedTask();
    }

    /** Feed a provider watch reply into the same shared Harness runtime; never starts a second loop. */
    public void acceptExternalReply(MessagingBackend.RemoteMessage reply) {
        if (closed || reply == null || reply.outgoing) return;
        if (!reply.id.isEmpty() && session.findMessage(reply.id) != null) return;
        Message message = new Message(reply.id, Message.Sender.OTHER_PERSON, "MATE",
                reply.content, reply.timestamp, Message.Status.RECEIVED);
        session.addMessage(message);
        if (session.userDirectControl()) {
            session.setStatus(CommunicationSession.Status.REPLY_RECEIVED);
            notifyChanged();
            return;
        }
        session.setStatus(CommunicationSession.Status.THINKING);
        notifyChanged();
        onExternalReply(session, message);
    }

    /** Private input transcription is display/state only; Gemini already received the corresponding audio. */
    public void recordUserTranscript(String text) {
        if (closed || session.userDirectControl()) return;
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) return;
        session.addMessage(new Message(Message.Sender.USER, "MATE", value, Message.Status.RECEIVED));
        if (session.status() == CommunicationSession.Status.NEEDS_USER_INPUT) {
            session.setPendingUserQuestion("");
            session.setStatus(CommunicationSession.Status.THINKING);
        }
        notifyChanged();
    }

    /** External live speech is visible product state but is not submitted again because Gemini heard the audio. */
    public void recordExternalTranscript(String text) {
        if (closed || session.userDirectControl()) return;
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) return;
        String person = session.targetPerson().isEmpty() ? "Other person" : session.targetPerson();
        session.addMessage(new Message(Message.Sender.OTHER_PERSON, "MATE", value, Message.Status.RECEIVED));
        session.setStatus(CommunicationSession.Status.THINKING);
        notifyChanged();
    }

    public boolean approvePending(String editedContent) { return !closed && !session.userDirectControl() && tools.approvePending(editedContent); }
    public boolean cancelPending() { return !closed && tools.cancelPending(); }

    @Override
    public void onAgentEvent(AgentEvent event) {
        traceRecorder.record(session, event);
        if (event == null) return;
        switch (event.type()) {
            case MODEL_TEXT:
                if (!closed) modelTurn.append(event.text());
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
        return status == CommunicationSession.Status.WAITING_FOR_REPLY
                || status == CommunicationSession.Status.REPLY_RECEIVED
                || status == CommunicationSession.Status.NEEDS_USER_INPUT
                || status == CommunicationSession.Status.WAITING_FOR_APPROVAL
                || status == CommunicationSession.Status.COMPLETED;
    }

    private void commitModelTurn() {
        String completed = modelTurn.take();
        if (completed.isEmpty()) return;
        if (speechAudience == SpeechAudience.EXTERNAL_WITH_MATE) {
            String recipient = session.targetPerson().isEmpty() ? "OTHER_PERSON" : session.targetPerson();
            session.addMessage(new Message(Message.Sender.MATE, recipient, completed, Message.Status.INFO));
        } else {
            session.addMessage(new Message(Message.Sender.MATE, "USER", completed, Message.Status.INFO));
        }
        notifyChanged();
    }

    @Override public void onSessionChanged(CommunicationSession changed) {
        if (!closed) notifyChanged();
    }

    @Override
    public void onApprovalRequired(CommunicationSession changed, PendingApproval approval) {
        if (closed) return;
        notifyChanged();
        emitStatus("Waiting for approval");
        if (listener != null) listener.onApprovalRequired(changed, approval);
    }

    @Override
    public void onExternalReply(CommunicationSession changed, Message message) {
        if (closed) return;
        if (changed.userDirectControl()) {
            changed.setStatus(CommunicationSession.Status.REPLY_RECEIVED);
            notifyChanged();
            return;
        }
        notifyChanged();
        emitStatus("Thinking");
        String person = changed.targetPerson().isEmpty() ? "OTHER_PERSON" : changed.targetPerson();
        harness.submitText("EXTERNAL_MESSAGE from " + person + ": " + message.content()
                + "\nThis is a delegated communication task. Continue the conversation yourself when the next step is routine and within the approved goal. "
                + "If the goal is achieved, call complete_task. If a new consequential decision is needed, call request_user_input.");
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
            case WAITING_FOR_APPROVAL: return "Waiting for approval";
            case SENDING: return "Sending";
            case WAITING_FOR_REPLY: return "Waiting for reply";
            case REPLY_RECEIVED: return "Reply received";
            case NEEDS_USER_INPUT: return "Needs your input";
            case COMPLETED: return "Completed";
            case STOPPED: return "Paused";
            case ERROR: return "Error";
            default: return "Thinking";
        }
    }
}
