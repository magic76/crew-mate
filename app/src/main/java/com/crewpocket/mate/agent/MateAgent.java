package com.crewpocket.mate.agent;

import com.crewpocket.mate.channel.ChannelAdapter;
import com.crewpocket.mate.model.CommunicationTask;
import com.crewpocket.mate.model.Message;

import org.json.JSONArray;
import org.json.JSONObject;

public class MateAgent {
    public interface Listener {
        void onTaskChanged(CommunicationTask task);
        void onApprovalRequired(CommunicationTask task, String draft, String reason);
        void onAgentEvent(String text);
    }

    public interface ContextSink {
        void pushContext(String text);
    }

    public interface ToolResultCallback {
        void onResult(JSONObject result);
    }

    private final ChannelAdapter channel;
    private final DecisionGate decisionGate;
    private final Listener listener;
    private ContextSink contextSink;
    private CommunicationTask currentTask;

    public MateAgent(ChannelAdapter channel, Listener listener) {
        this.channel = channel;
        this.listener = listener;
        this.decisionGate = new DecisionGate();
    }

    public void setContextSink(ContextSink contextSink) {
        this.contextSink = contextSink;
    }

    public CommunicationTask getCurrentTask() {
        return currentTask;
    }

    public void handleToolCall(String name, JSONObject args, ToolResultCallback callback) {
        try {
            if ("create_task".equals(name)) {
                String contact = args.optString("contact", "Unknown contact");
                String goal = args.optString("goal", "Communication task");
                currentTask = new CommunicationTask(contact, goal);
                JSONArray context = args.optJSONArray("private_context");
                if (context != null) {
                    for (int i = 0; i < context.length(); i++) currentTask.addPrivateContext(context.optString(i));
                }
                currentTask.state = CommunicationTask.State.PLANNING;
                notifyChanged();
                callback.onResult(ok().put("task_id", currentTask.id).put("status", "created"));
                return;
            }

            if (currentTask == null) {
                callback.onResult(error("No active communication task. Call create_task first."));
                return;
            }

            if ("update_task_context".equals(name)) {
                String value = args.optString("context", "");
                currentTask.addPrivateContext(value);
                notifyChanged();
                callback.onResult(ok().put("status", "updated"));
                return;
            }

            if ("draft_message".equals(name)) {
                currentTask.pendingDraft = args.optString("text", "").trim();
                notifyChanged();
                callback.onResult(ok().put("status", "drafted"));
                return;
            }

            if ("send_message".equals(name)) {
                sendMessage(args, callback);
                return;
            }

            if ("ask_user".equals(name)) {
                currentTask.state = CommunicationTask.State.NEEDS_USER;
                notifyChanged();
                callback.onResult(ok().put("status", "waiting_for_user"));
                return;
            }

            if ("complete_task".equals(name)) {
                currentTask.summary = args.optString("summary", "Task completed.");
                currentTask.pendingDraft = "";
                currentTask.state = CommunicationTask.State.COMPLETED;
                notifyChanged();
                callback.onResult(ok().put("status", "completed"));
                return;
            }

            callback.onResult(error("Unknown tool: " + name));
        } catch (Exception e) {
            callback.onResult(error(e.getMessage() == null ? "Tool execution failed." : e.getMessage()));
        }
    }

    private void sendMessage(JSONObject args, final ToolResultCallback callback) throws Exception {
        final String text = args.optString("text", currentTask.pendingDraft).trim();
        boolean userApproved = args.optBoolean("user_approved", false);
        if (text.isEmpty()) {
            callback.onResult(error("Message text is empty."));
            return;
        }

        DecisionGate.Result decision = decisionGate.evaluate(text);
        if (decision.requiresApproval && !userApproved) {
            currentTask.pendingDraft = text;
            currentTask.state = CommunicationTask.State.NEEDS_USER;
            notifyChanged();
            if (listener != null) listener.onApprovalRequired(currentTask, text, decision.reason);
            callback.onResult(ok()
                    .put("status", "blocked_for_user_approval")
                    .put("reason", decision.reason));
            pushContext("SYSTEM_EVENT: The outgoing message was blocked by the Decision Gate because: "
                    + decision.reason + " Ask the user for explicit approval before calling send_message again with user_approved=true.");
            return;
        }

        currentTask.pendingDraft = "";
        currentTask.addExternalMessage(new Message(Message.Sender.MATE, text));
        currentTask.state = CommunicationTask.State.WAITING;
        notifyChanged();
        callback.onResult(ok().put("status", "sent").put("waiting_for_reply", true));

        channel.send(currentTask, text, new ChannelAdapter.Callback() {
            @Override public void onDelivered() {
                if (listener != null) listener.onAgentEvent("Message delivered to " + currentTask.contact + ".");
            }

            @Override public void onReply(String reply) {
                currentTask.addExternalMessage(new Message(Message.Sender.CONTACT, reply));
                currentTask.state = CommunicationTask.State.COMMUNICATING;
                notifyChanged();
                pushContext("EXTERNAL_MESSAGE from " + currentTask.contact + ": " + reply
                        + "\nContinue pursuing the task goal. Do not invent facts. If a new decision is outside the user's known preferences or constraints, call ask_user and ask the user by voice.");
            }

            @Override public void onError(String message) {
                currentTask.state = CommunicationTask.State.PAUSED;
                notifyChanged();
                pushContext("SYSTEM_EVENT: Sending failed: " + message);
            }
        });
    }

    private void notifyChanged() {
        if (listener != null) listener.onTaskChanged(currentTask);
    }

    private void pushContext(String text) {
        if (contextSink != null) contextSink.pushContext(text);
    }

    private static JSONObject ok() {
        try { return new JSONObject().put("ok", true); }
        catch (Exception ignored) { return new JSONObject(); }
    }

    private static JSONObject error(String message) {
        try { return new JSONObject().put("ok", false).put("error", message == null ? "Unknown error" : message); }
        catch (Exception ignored) { return new JSONObject(); }
    }
}
