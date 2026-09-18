package com.crewpocket.mate.agent;

import com.crewpocket.mate.model.CommunicationSession;
import com.magic76.crew.agent.ToolCall;
import com.magic76.crew.agent.ToolExecutor;
import com.magic76.crew.agent.ToolRegistry;
import com.magic76.crew.agent.ToolResult;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Product-owned tools for an in-person conversation only. */
public final class CrewMateToolRegistry {
    public interface Listener {
        void onSessionChanged(CommunicationSession session);
        void onUserInputRequested(CommunicationSession session, String question, String reason);
    }

    private final CommunicationSession session;
    private final Listener listener;
    private final ToolRegistry registry = new ToolRegistry();

    public CrewMateToolRegistry(CommunicationSession session, Listener listener) {
        if (session == null) throw new IllegalArgumentException("session is null");
        this.session = session;
        this.listener = listener;
        registerTools();
    }

    public ToolRegistry registry() { return registry; }

    private void registerTools() {
        registry.register("find_contact", new ToolExecutor() {
            @Override public void execute(ToolCall call, Completion completion) {
                String query = arg(call, "query");
                String goal = arg(call, "goal");
                if (query.isEmpty()) {
                    completion.complete(ToolResult.failure(call.id(), "PERSON_REQUIRED", "Person is empty"));
                    return;
                }

                String personId = "local-" + query.toLowerCase(Locale.US).replace(' ', '-');
                session.setTarget(personId, query);
                session.setGoal(goal);
                session.setOutcomeSummary("");
                session.setUserDirectControl(false);
                session.setStatus(CommunicationSession.Status.THINKING);
                notifyChanged();

                Map<String, Object> payload = new LinkedHashMap<String, Object>();
                payload.put("person_id", personId);
                payload.put("display_name", query);
                payload.put("goal", session.goal());
                completion.complete(ToolResult.success(call.id(), payload));
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
}
