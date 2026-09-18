package com.crewpocket.mate.agent;

import com.crewpocket.mate.channel.MessagingBackend;
import com.crewpocket.mate.model.CommunicationSession;
import com.magic76.crew.agent.ToolCall;
import com.magic76.crew.agent.ToolExecutor;
import com.magic76.crew.agent.ToolRegistry;
import com.magic76.crew.agent.ToolResult;

import java.util.LinkedHashMap;
import java.util.Map;

/** Product-owned tools for an in-person conversation only. */
public final class CrewMateToolRegistry {
    public interface Listener {
        void onSessionChanged(CommunicationSession session);
        void onUserInputRequested(CommunicationSession session, String question, String reason);
    }

    private final CommunicationSession session;
    private final MessagingBackend backend;
    private final Listener listener;
    private final ToolRegistry registry = new ToolRegistry();

    public CrewMateToolRegistry(CommunicationSession session, MessagingBackend backend, Listener listener) {
        if (session == null) throw new IllegalArgumentException("session is null");
        if (backend == null) throw new IllegalArgumentException("backend is null");
        this.session = session;
        this.backend = backend;
        this.listener = listener;
        registerTools();
    }

    public ToolRegistry registry() { return registry; }

    private void registerTools() {
        registry.register("find_contact", new ToolExecutor() {
            @Override public void execute(final ToolCall call, final Completion completion) {
                final String query = arg(call, "query");
                final String goal = arg(call, "goal");
                if (query.isEmpty()) {
                    completion.complete(ToolResult.failure(call.id(), "PERSON_REQUIRED", "Person is empty"));
                    return;
                }
                backend.findContact(query, new MessagingBackend.FindCallback() {
                    @Override public void onFound(MessagingBackend.Contact contact) {
                        session.setTarget(contact.id, contact.displayName);
                        session.setGoal(goal);
                        session.setOutcomeSummary("");
                        session.setUserDirectControl(false);
                        session.setStatus(CommunicationSession.Status.THINKING);
                        notifyChanged();
                        Map<String, Object> payload = new LinkedHashMap<String, Object>();
                        payload.put("person_id", contact.id);
                        payload.put("display_name", contact.displayName);
                        payload.put("goal", session.goal());
                        completion.complete(ToolResult.success(call.id(), payload));
                    }

                    @Override public void onError(String message) {
                        completion.complete(ToolResult.failure(call.id(), "PERSON_NOT_FOUND", safe(message)));
                    }
                });
            }
        });

        registry.register("request_user_input", new ToolExecutor() {
            @Override public void execute(ToolCall call, Completion completion) {
                String question = arg(call, "question");
                String reason = arg(call, "reason");
                if (question.isEmpty()) {
                    completion.complete(ToolResult.failure(call.id(), "QUESTION_REQUIRED", "Question is empty"));
                    return;
                }
                session.setPendingUserQuestion(question);
                session.setStatus(CommunicationSession.Status.NEEDS_USER_INPUT);
                notifyChanged();
                if (listener != null) listener.onUserInputRequested(session, question, reason);
                Map<String, Object> payload = new LinkedHashMap<String, Object>();
                payload.put("status", "waiting_for_user");
                completion.complete(ToolResult.success(call.id(), payload));
            }
        });

        registry.register("complete_task", new ToolExecutor() {
            @Override public void execute(ToolCall call, Completion completion) {
                String summary = arg(call, "summary");
                if (summary.isEmpty()) {
                    completion.complete(ToolResult.failure(call.id(), "SUMMARY_REQUIRED", "Outcome summary is empty"));
                    return;
                }
                session.setOutcomeSummary(summary);
                session.setPendingUserQuestion("");
                session.setStatus(CommunicationSession.Status.COMPLETED);
                notifyChanged();
                Map<String, Object> payload = new LinkedHashMap<String, Object>();
                payload.put("status", "completed");
                payload.put("summary", summary);
                completion.complete(ToolResult.success(call.id(), payload));
            }
        });
    }

    private void notifyChanged() {
        if (listener != null) listener.onSessionChanged(session);
    }

    private static String arg(ToolCall call, String key) {
        if (call == null || key == null) return "";
        Object value = call.arguments().get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
