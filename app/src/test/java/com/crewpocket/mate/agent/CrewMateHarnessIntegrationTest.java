package com.crewpocket.mate.agent;

import com.crewpocket.mate.channel.FakeMessagingBackend;
import com.crewpocket.mate.model.CommunicationSession;
import com.crewpocket.mate.model.Message;
import com.crewpocket.mate.model.PendingApproval;
import com.magic76.crew.agent.AgentEvent;
import com.magic76.crew.agent.AgentHarness;
import com.magic76.crew.agent.ModelEvent;
import com.magic76.crew.agent.ModelSession;
import com.magic76.crew.agent.SessionConfig;
import com.magic76.crew.agent.ToolCall;
import com.magic76.crew.agent.ToolExecutor;
import com.magic76.crew.agent.ToolRegistry;
import com.magic76.crew.agent.ToolResult;
import com.magic76.crew.agent.ToolSpec;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class CrewMateHarnessIntegrationTest {

    @Test
    public void agentSpecExposesExpectedTools() {
        CrewMateAgentSpec spec = new CrewMateAgentSpec();
        List<String> names = new ArrayList<String>();
        for (ToolSpec tool : spec.tools()) names.add(tool.name());
        assertEquals(5, names.size());
        assertTrue(names.contains("find_contact"));
        assertTrue(names.contains("get_conversation"));
        assertTrue(names.contains("draft_message"));
        assertTrue(names.contains("send_message"));
        assertTrue(names.contains("request_user_input"));
    }

    @Test
    public void undeclaredToolIsRejectedBySharedHarness() {
        RecordingModelSession model = new RecordingModelSession();
        AgentHarness harness = new AgentHarness(new CrewMateAgentSpec(), model, new ToolRegistry(), null);
        harness.start();
        model.emit(ModelEvent.toolCall(new ToolCall("bad-1", "not_declared", Collections.<String, Object>emptyMap())));
        assertNotNull(model.lastToolResult);
        assertFalse(model.lastToolResult.success());
        assertEquals("bad-1", model.lastToolResult.callId());
        assertEquals("TOOL_NOT_AVAILABLE", model.lastToolResult.errorCode());
        harness.close();
    }

    @Test
    public void toolResultPreservesCallIdAndDuplicateCompletionIsIgnored() {
        ToolRegistry registry = new ToolRegistry();
        registry.register("once", new ToolExecutor() {
            @Override public void execute(ToolCall call, Completion completion) {
                completion.complete(ToolResult.success(call.id(), Collections.<String, Object>emptyMap()));
                completion.complete(ToolResult.failure(call.id(), "SECOND", "must be ignored"));
            }
        });
        final AtomicInteger count = new AtomicInteger();
        final AtomicReference<ToolResult> result = new AtomicReference<ToolResult>();
        registry.execute(new ToolCall("call-42", "once", Collections.<String, Object>emptyMap()), new ToolExecutor.Completion() {
            @Override public void complete(ToolResult value) {
                count.incrementAndGet();
                result.set(value);
            }
        });
        assertEquals(1, count.get());
        assertNotNull(result.get());
        assertEquals("call-42", result.get().callId());
        assertTrue(result.get().success());
    }

    @Test
    public void sendMessageCannotExecuteBeforeApproval() {
        FakeMessagingBackend backend = new FakeMessagingBackend(0L);
        CommunicationSession session = new CommunicationSession();
        CrewMateToolRegistry tools = new CrewMateToolRegistry(session, backend, noOpListener());
        execute(tools.registry(), call("find-1", "find_contact", map("query", "John", "goal", "Dinner tomorrow")));
        execute(tools.registry(), call("draft-1", "draft_message", map("content", "John，明天晚上有空一起吃飯嗎？")));

        final AtomicInteger completed = new AtomicInteger();
        tools.registry().execute(call("send-1", "send_message", map("content", "John，明天晚上有空一起吃飯嗎？")),
                new ToolExecutor.Completion() {
                    @Override public void complete(ToolResult result) { completed.incrementAndGet(); }
                });

        assertEquals(0, backend.sendCount());
        assertEquals(0, completed.get());
        assertNotNull(session.pendingApproval());
        assertEquals(CommunicationSession.Status.WAITING_FOR_APPROVAL, session.status());
        backend.shutdown();
    }

    @Test
    public void approvedMessageCanOnlySendOnce() {
        FakeMessagingBackend backend = new FakeMessagingBackend(1000L);
        CommunicationSession session = new CommunicationSession();
        CrewMateToolRegistry tools = new CrewMateToolRegistry(session, backend, noOpListener());
        execute(tools.registry(), call("find-1", "find_contact", map("query", "John", "goal", "Dinner tomorrow")));
        execute(tools.registry(), call("draft-1", "draft_message", map("content", "Dinner tomorrow?")));
        tools.registry().execute(call("send-1", "send_message", map("content", "Dinner tomorrow?")), new ToolExecutor.Completion() {
            @Override public void complete(ToolResult result) {}
        });

        assertTrue(tools.approvePending(null));
        assertFalse(tools.approvePending(null));
        assertEquals(1, backend.sendCount());
        backend.shutdown();
    }

    @Test
    public void conversationMessagesKeepInsertionOrder() {
        CommunicationSession session = new CommunicationSession();
        Message first = new Message("1", Message.Sender.USER, "MATE", "first", 1L, Message.Status.RECEIVED);
        Message second = new Message("2", Message.Sender.MATE, "John", "second", 2L, Message.Status.DELIVERED);
        Message third = new Message("3", Message.Sender.OTHER_PERSON, "MATE", "third", 3L, Message.Status.RECEIVED);
        session.addMessage(first);
        session.addMessage(second);
        session.addMessage(third);
        assertEquals("1", session.messages().get(0).id);
        assertEquals("2", session.messages().get(1).id);
        assertEquals("3", session.messages().get(2).id);
    }

    @Test
    public void traceNeverSerializesPrivateMessageContent() {
        CommunicationSession session = new CommunicationSession();
        AgentTraceRecorder trace = new AgentTraceRecorder();
        String secret = "private-message-must-not-be-logged";
        trace.record(session, AgentEvent.text(AgentEvent.Type.USER_TEXT, secret));
        trace.record(session, AgentEvent.simple(AgentEvent.Type.STARTED));
        String serialized = trace.serialize();
        assertFalse(serialized.contains(secret));
        assertTrue(serialized.contains("STARTED"));
    }

    @Test
    public void fakeEndToEndFlowShowsReplyAfterApproval() throws Exception {
        final CountDownLatch replyLatch = new CountDownLatch(1);
        FakeMessagingBackend backend = new FakeMessagingBackend(0L);
        CommunicationSession session = new CommunicationSession();
        CrewMateToolRegistry tools = new CrewMateToolRegistry(session, backend, new CrewMateToolRegistry.Listener() {
            @Override public void onSessionChanged(CommunicationSession session) {}
            @Override public void onApprovalRequired(CommunicationSession session, PendingApproval approval) {}
            @Override public void onExternalReply(CommunicationSession session, Message message) { replyLatch.countDown(); }
            @Override public void onUserInputRequested(CommunicationSession session, String question, String reason) {}
        });

        execute(tools.registry(), call("find", "find_contact", map(
                "query", "John",
                "goal", "幫我問 John 明天晚上有沒有空吃飯")));
        execute(tools.registry(), call("draft", "draft_message", map(
                "content", "John，明天晚上有空一起吃飯嗎？")));

        final AtomicReference<ToolResult> sendResult = new AtomicReference<ToolResult>();
        tools.registry().execute(call("send", "send_message", map(
                "content", "John，明天晚上有空一起吃飯嗎？")), new ToolExecutor.Completion() {
            @Override public void complete(ToolResult result) { sendResult.set(result); }
        });

        assertNotNull(session.pendingApproval());
        assertEquals(0, backend.sendCount());
        assertTrue(tools.approvePending(null));
        assertTrue(replyLatch.await(2, TimeUnit.SECONDS));
        assertEquals(1, backend.sendCount());
        assertNotNull(sendResult.get());
        assertEquals("send", sendResult.get().callId());
        assertTrue(sendResult.get().success());

        boolean sawReply = false;
        for (Message message : session.messages()) {
            if (message.sender == Message.Sender.OTHER_PERSON && message.content().contains("7 點")) {
                sawReply = true;
            }
        }
        assertTrue(sawReply);
        backend.shutdown();
    }

    private static CrewMateToolRegistry.Listener noOpListener() {
        return new CrewMateToolRegistry.Listener() {
            @Override public void onSessionChanged(CommunicationSession session) {}
            @Override public void onApprovalRequired(CommunicationSession session, PendingApproval approval) {}
            @Override public void onExternalReply(CommunicationSession session, Message message) {}
            @Override public void onUserInputRequested(CommunicationSession session, String question, String reason) {}
        };
    }

    private static ToolResult execute(ToolRegistry registry, ToolCall call) {
        final AtomicReference<ToolResult> result = new AtomicReference<ToolResult>();
        registry.execute(call, new ToolExecutor.Completion() {
            @Override public void complete(ToolResult value) { result.set(value); }
        });
        assertNotNull("Expected synchronous fake tool completion for " + call.name(), result.get());
        return result.get();
    }

    private static ToolCall call(String id, String name, Map<String, Object> args) {
        return new ToolCall(id, name, args);
    }

    private static Map<String, Object> map(String k1, Object v1) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put(k1, v1);
        return map;
    }

    private static Map<String, Object> map(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> map = map(k1, v1);
        map.put(k2, v2);
        return map;
    }

    private static final class RecordingModelSession implements ModelSession {
        ModelSession.Listener listener;
        ToolResult lastToolResult;

        @Override public void start(SessionConfig config, ModelSession.Listener listener) { this.listener = listener; }
        @Override public void sendUserText(String text) {}
        @Override public void sendUserAudio(byte[] audio) {}
        @Override public void sendToolResult(ToolResult result) { lastToolResult = result; }
        @Override public void interrupt() {}
        @Override public void close() {}
        void emit(ModelEvent event) { listener.onModelEvent(event); }
    }
}
