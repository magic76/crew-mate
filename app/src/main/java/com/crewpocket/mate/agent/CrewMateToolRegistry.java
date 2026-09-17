package com.crewpocket.mate.agent;

import com.crewpocket.mate.channel.MessagingBackend;
import com.crewpocket.mate.model.CommunicationSession;
import com.crewpocket.mate.model.Message;
import com.crewpocket.mate.model.PendingApproval;
import com.magic76.crew.agent.ToolCall;
import com.magic76.crew.agent.ToolExecutor;
import com.magic76.crew.agent.ToolRegistry;
import com.magic76.crew.agent.ToolResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Product-owned tool implementations. Delegation and approval stay here, never in agent-core. */
public final class CrewMateToolRegistry {
    public interface Listener {
        void onSessionChanged(CommunicationSession session);
        void onApprovalRequired(CommunicationSession session, PendingApproval approval);
        void onExternalReply(CommunicationSession session, Message message);
        void onUserInputRequested(CommunicationSession session, String question, String reason);
    }

    private static final String FIRST_MESSAGE_REASON =
            "ASK_FIRST_MESSAGE: approving this first message delegates routine conversation within the stated goal.";
    private static final String HIGH_RISK_REASON =
            "HIGH_RISK: this message may create a payment, cancellation, booking, legal, sensitive-data, or material commitment.";

    private final CommunicationSession session;
    private final MessagingBackend backend;
    private final Listener listener;
    private final ToolRegistry registry = new ToolRegistry();
    private PendingSend pendingSend;

    private static final class PendingSend {
        final ToolCall call;
        final Message message;
        final PendingApproval approval;
        final ToolExecutor.Completion completion;
        boolean consumed;

        PendingSend(ToolCall call, Message message, PendingApproval approval, ToolExecutor.Completion completion) {
            this.call = call;
            this.message = message;
            this.approval = approval;
            this.completion = completion;
        }
    }

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
                    completion.complete(ToolResult.failure(call.id(), "CONTACT_REQUIRED", "Contact query is empty"));
                    return;
                }
                backend.findContact(query, new MessagingBackend.FindCallback() {
                    @Override public void onFound(MessagingBackend.Contact contact) {
                        session.setTarget(contact.id, contact.displayName);
                        session.setGoal(goal);
                        session.setOutcomeSummary("");
                        session.setDelegationAuthorized(false);
                        session.setUserDirectControl(false);
                        session.setStatus(CommunicationSession.Status.THINKING);
                        notifyChanged();
                        Map<String, Object> payload = new LinkedHashMap<String, Object>();
                        payload.put("contact_id", contact.id);
                        payload.put("display_name", contact.displayName);
                        payload.put("goal", session.goal());
                        completion.complete(ToolResult.success(call.id(), payload));
                    }

                    @Override public void onError(String message) {
                        completion.complete(ToolResult.failure(call.id(), "CONTACT_NOT_FOUND", safe(message)));
                    }
                });
            }
        });

        registry.register("get_conversation", new ToolExecutor() {
            @Override public void execute(final ToolCall call, final Completion completion) {
                final MessagingBackend.Contact contact = currentContact();
                if (contact == null) {
                    completion.complete(ToolResult.failure(call.id(), "CONTACT_REQUIRED", "Call find_contact first"));
                    return;
                }
                backend.getConversation(contact, new MessagingBackend.ConversationCallback() {
                    @Override public void onLoaded(List<MessagingBackend.RemoteMessage> remoteMessages) {
                        int count = 0;
                        if (remoteMessages != null) {
                            for (MessagingBackend.RemoteMessage remote : remoteMessages) {
                                if (remote == null) continue;
                                Message message = new Message(
                                        remote.id,
                                        remote.outgoing ? Message.Sender.MATE : Message.Sender.OTHER_PERSON,
                                        remote.outgoing ? contact.displayName : "MATE",
                                        remote.content,
                                        remote.timestamp,
                                        remote.outgoing ? Message.Status.SENT : Message.Status.RECEIVED);
                                session.addMessage(message);
                                count++;
                            }
                        }
                        notifyChanged();
                        Map<String, Object> payload = new LinkedHashMap<String, Object>();
                        payload.put("message_count", count);
                        completion.complete(ToolResult.success(call.id(), payload));
                    }

                    @Override public void onError(String message) {
                        completion.complete(ToolResult.failure(call.id(), "CONVERSATION_READ_FAILED", safe(message)));
                    }
                });
            }
        });

        registry.register("draft_message", new ToolExecutor() {
            @Override public void execute(ToolCall call, Completion completion) {
                String content = arg(call, "content");
                if (currentContact() == null) {
                    completion.complete(ToolResult.failure(call.id(), "CONTACT_REQUIRED", "Call find_contact first"));
                    return;
                }
                if (content.isEmpty()) {
                    completion.complete(ToolResult.failure(call.id(), "EMPTY_DRAFT", "Draft content is empty"));
                    return;
                }
                Message draft = new Message(Message.Sender.MATE, session.targetPerson(), content, Message.Status.DRAFT);
                session.addMessage(draft);
                session.setStatus(CommunicationSession.Status.THINKING);
                notifyChanged();
                Map<String, Object> payload = new LinkedHashMap<String, Object>();
                payload.put("draft_message_id", draft.id);
                payload.put("status", "drafted");
                completion.complete(ToolResult.success(call.id(), payload));
            }
        });

        registry.register("send_message", new ToolExecutor() {
            @Override public void execute(ToolCall call, Completion completion) {
                requestSend(call, completion);
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
                synchronized (CrewMateToolRegistry.this) {
                    if (pendingSend != null && !pendingSend.consumed) {
                        completion.complete(ToolResult.failure(call.id(), "APPROVAL_PENDING", "Cannot complete while an outbound message awaits approval"));
                        return;
                    }
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

    private void requestSend(ToolCall call, ToolExecutor.Completion completion) {
        String content = arg(call, "content");
        PendingSend automatic = null;
        synchronized (this) {
            if (session.userDirectControl()) {
                completion.complete(ToolResult.failure(call.id(), "USER_DIRECT_CONTROL",
                        "The user is speaking to the other person directly. Do not send until control returns to Mate."));
                return;
            }
            if (pendingSend != null && !pendingSend.consumed) {
                completion.complete(ToolResult.failure(call.id(), "APPROVAL_PENDING", "Another message is awaiting user approval"));
                return;
            }
            Message draft = session.latestDraft();
            if (draft == null) {
                completion.complete(ToolResult.failure(call.id(), "DRAFT_REQUIRED", "Call draft_message before send_message"));
                return;
            }
            if (content.isEmpty()) content = draft.content();
            if (!draft.content().equals(content)) {
                completion.complete(ToolResult.failure(call.id(), "DRAFT_MISMATCH", "send_message must use the current draft content"));
                return;
            }

            boolean highRisk = isHighRisk(content);
            boolean needsApproval = !session.delegationAuthorized() || highRisk;
            if (needsApproval) {
                draft.updateStatus(Message.Status.PENDING_APPROVAL);
                PendingApproval approval = new PendingApproval(
                        call.id(), draft.id, content, highRisk ? HIGH_RISK_REASON : FIRST_MESSAGE_REASON);
                pendingSend = new PendingSend(call, draft, approval, completion);
                session.setPendingApproval(approval);
                session.setStatus(CommunicationSession.Status.WAITING_FOR_APPROVAL);
                notifyChanged();
                if (listener != null) listener.onApprovalRequired(session, approval);
                return;
            }

            draft.updateStatus(Message.Status.SENDING);
            session.setStatus(CommunicationSession.Status.SENDING);
            automatic = new PendingSend(call, draft, null, completion);
            automatic.consumed = true;
            notifyChanged();
        }
        performSend(automatic);
    }

    public boolean approvePending(String editedContent) {
        final PendingSend approved;
        synchronized (this) {
            if (session.userDirectControl()) return false;
            if (pendingSend == null || pendingSend.consumed) return false;
            approved = pendingSend;
            approved.consumed = true;
            String content = editedContent == null || editedContent.trim().isEmpty()
                    ? approved.approval.content()
                    : editedContent.trim();
            approved.approval.updateContent(content);
            approved.approval.updateState(PendingApproval.State.APPROVED);
            approved.message.updateContent(content);
            approved.message.updateStatus(Message.Status.SENDING);
            session.setDelegationAuthorized(true);
            session.setStatus(CommunicationSession.Status.SENDING);
            notifyChanged();
        }
        performSend(approved);
        return true;
    }

    private void performSend(final PendingSend send) {
        final MessagingBackend.Contact contact = currentContact();
        if (contact == null) {
            failSend(send, "CONTACT_REQUIRED", "Contact disappeared before send");
            return;
        }

        backend.sendMessage(contact, send.message.content(), new MessagingBackend.SendCallback() {
            @Override public void onDelivered(String providerMessageId) {
                send.message.updateStatus(Message.Status.SENT);
                if (send.approval != null) send.approval.updateState(PendingApproval.State.SENT);
                session.setPendingApproval(null);
                session.setStatus(CommunicationSession.Status.WAITING_FOR_REPLY);
                synchronized (CrewMateToolRegistry.this) {
                    if (pendingSend == send) pendingSend = null;
                }
                notifyChanged();
                Map<String, Object> payload = new LinkedHashMap<String, Object>();
                payload.put("status", "sent");
                payload.put("provider_message_id", safe(providerMessageId));
                payload.put("delegation_authorized", session.delegationAuthorized());
                send.completion.complete(ToolResult.success(send.call.id(), payload));
            }

            @Override public void onReply(MessagingBackend.RemoteMessage reply) {
                if (reply == null) return;
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
                if (listener != null) listener.onExternalReply(session, message);
            }

            @Override public void onError(String message) {
                failSend(send, "SEND_FAILED", safe(message));
            }
        });
    }

    public boolean cancelPending() {
        PendingSend cancelled;
        synchronized (this) {
            if (pendingSend == null || pendingSend.consumed) return false;
            cancelled = pendingSend;
            cancelled.consumed = true;
            pendingSend = null;
            cancelled.approval.updateState(PendingApproval.State.CANCELLED);
            cancelled.message.updateStatus(Message.Status.CANCELLED);
            session.setPendingApproval(null);
            session.setStatus(CommunicationSession.Status.NEEDS_USER_INPUT);
            notifyChanged();
        }
        cancelled.completion.complete(ToolResult.failure(cancelled.call.id(), "USER_CANCELLED", "User cancelled the outbound message"));
        return true;
    }

    private void failSend(PendingSend pending, String code, String message) {
        if (pending.approval != null) pending.approval.updateState(PendingApproval.State.FAILED);
        pending.message.updateStatus(Message.Status.FAILED);
        session.setPendingApproval(null);
        session.setStatus(CommunicationSession.Status.ERROR);
        synchronized (this) { if (pendingSend == pending) pendingSend = null; }
        notifyChanged();
        pending.completion.complete(ToolResult.failure(pending.call.id(), code, message));
    }

    private boolean isHighRisk(String content) {
        String value = safe(content).toLowerCase(Locale.US);
        String[] markers = new String[]{
                "$", "usd", "twd", "thb", "payment", "pay ", "price", "fee", "charge", "deposit",
                "refund", "cancel", "book ", "booking", "reserve", "purchase", "buy ", "contract",
                "sign ", "promise", "commit", "password", "otp", "verification code", "credit card",
                "bank transfer", "wire transfer",
                "付款", "付費", "價格", "費用", "訂金", "退款", "取消", "預訂", "訂房", "購買",
                "合約", "簽署", "承諾", "保證", "密碼", "驗證碼", "信用卡", "銀行", "匯款"
        };
        for (String marker : markers) if (value.contains(marker)) return true;
        return false;
    }

    private MessagingBackend.Contact currentContact() {
        String id = session.targetPersonId();
        String name = session.targetPerson();
        if (id.isEmpty() || name.isEmpty()) return null;
        return new MessagingBackend.Contact(id, name);
    }

    private void notifyChanged() {
        if (listener != null) listener.onSessionChanged(session);
    }

    private static String arg(ToolCall call, String key) {
        if (call == null || key == null) return "";
        Object value = call.arguments().get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
