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
        registry.register("update_task_consensus", new ToolExecutor() {
            @Override public void execute(ToolCall call, Completion completion) {
                String target = arg(call, "target");
                String goal = arg(call, "goal");
                String constraints = arg(call, "constraints");
                String escalationBoundary = arg(call, "escalation_boundary");
                boolean ready = boolArg(call, "ready");

                if (target.isEmpty()) {
                    completion.complete(ToolResult.failure(call.id(), "PERSON_REQUIRED", "Target person is empty"));
                    return;
                }
                if (goal.isEmpty()) {
                    completion.complete(ToolResult.failure(call.id(), "GOAL_REQUIRED", "Goal is empty"));
                    return;
                }

                String personId = "local-" + target.toLowerCase(Locale.US).replace(' ', '-');
                session.setConsensus(personId, target, goal, constraints, escalationBoundary, ready);
                session.setOutcomeSummary("");
                session.setUserDirectControl(false);
                if (ready) session.setPendingUserQuestion("");
                session.setStatus(CommunicationSession.Status.THINKING);
                notifyChanged();

                Map<String, Object> payload = new LinkedHashMap<String, Object>();
                payload.put("person_id", personId);
                payload.put("display_name", target);
                payload.put("goal", session.goal());
                payload.put("constraints", session.constraints());
                payload.put("escalation_boundary", session.escalationBoundary());
                payload.put("ready", session.consensusReady());
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
                session.setConsensusReady(false);
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

    private static boolean boolArg(ToolCall call, String key) {
        if (call == null || key == null) return false;
        Object value = call.arguments().get(key);
        if (value instanceof Boolean) return (Boolean) value;
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }
}
