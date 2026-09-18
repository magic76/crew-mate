package com.crewpocket.mate.agent;

import com.crewpocket.mate.model.CommunicationSession;
import com.crewpocket.mate.model.Message;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class CrewMateHarnessIntegrationTest {

    @Test
    public void agentSpecExposesOnlyInPersonTools() {
        CrewMateAgentSpec spec = new CrewMateAgentSpec();
        List<String> names = new ArrayList<String>();
        for (ToolSpec tool : spec.tools()) names.add(tool.name());
        assertEquals(3, names.size());
        assertTrue(names.contains("update_task_consensus"));
        assertTrue(names.contains("request_user_input"));
        assertTrue(names.contains("complete_task"));
        assertFalse(names.contains("draft_message"));
        assertFalse(names.contains("send_message"));
        assertFalse(names.contains("get_conversation"));
    }

    @Test
    public void undeclaredToolIsRejectedBySharedHarness() {
        RecordingModelSession model = new RecordingModelSession();
        AgentHarness harness = new AgentHarness(new CrewMateAgentSpec(), model, new ToolRegistry(), null);
        harness.start();
        model.emit(ModelEvent.toolCall(new ToolCall("bad-1", "send_message", Collections.<String, Object>emptyMap())));
        assertNotNull(model.lastToolResult);
        assertFalse(model.lastToolResult.success());
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
    public void taskConsensusStoresSharedGoalConstraintsAndBoundary() {
        CommunicationSession session = new CommunicationSession();
        CrewMateToolRegistry tools = new CrewMateToolRegistry(session, noOpListener());

        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("target", "Reception");
        args.put("goal", "Ask for late checkout until 2 PM");
        args.put("constraints", "Prefer free; up to 500 THB is acceptable");
        args.put("escalation_boundary", "Ask me before agreeing above 500 THB");
        args.put("ready", true);

        ToolResult result = execute(tools.registry(),
                call("consensus", "update_task_consensus", args));

        assertTrue(result.success());
        assertEquals("Reception", session.targetPerson());
        assertEquals("Ask for late checkout until 2 PM", session.goal());
        assertEquals("Prefer free; up to 500 THB is acceptable", session.constraints());
        assertEquals("Ask me before agreeing above 500 THB", session.escalationBoundary());
        assertTrue(session.consensusReady());
    }

    @Test
    public void stageOneClarificationMustBeSpokenPrivately() {
        String prompt = new CrewMateAgentSpec().systemPrompt();

        assertTrue(prompt.contains("clarification is a spoken conversation"));
        assertTrue(prompt.contains("say that same concise question aloud"));
        assertTrue(prompt.contains("PRIVATE_TO_MATE"));
    }

    @Test
    public void agentPromptDoesNotRequirePaymentForUnrelatedTasks() {
        String prompt = new CrewMateAgentSpec().systemPrompt();

        assertTrue(prompt.contains("Payment fields are OPTIONAL"));
        assertTrue(prompt.contains("leave both empty"));
        assertTrue(prompt.contains("do not ask payment questions"));
    }

    @Test
    public void taskConsensusStoresPaymentPreferenceAndFallbackPolicy() {
        CommunicationSession session = new CommunicationSession();
        CrewMateToolRegistry tools = new CrewMateToolRegistry(session, noOpListener());

        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("target", "Restaurant cashier");
        args.put("goal", "Pay the bill");
        args.put("constraints", "");
        args.put("escalation_boundary", "Ask me if the requested payment method changes");
        args.put("payment_preference", "Credit card");
        args.put("payment_fallback", "");
        args.put("ready", true);

        ToolResult result = execute(tools.registry(),
                call("payment", "update_task_consensus", args));

        assertTrue(result.success());
        assertEquals("Credit card", session.paymentPreference());
        assertEquals("", session.paymentFallback());
        assertTrue(session.consensusReady());
    }

    @Test
    public void agentPromptEscalatesUnapprovedPaymentMethodChanges() {
        String prompt = new CrewMateAgentSpec().systemPrompt();

        assertTrue(prompt.contains("cash only"));
        assertTrue(prompt.contains("request_user_input"));
        assertTrue(prompt.contains("payment"));
    }

    @Test
    public void incompleteConsensusCannotBecomeReadyWithoutTargetAndGoal() {
        CommunicationSession session = new CommunicationSession();
        session.setConsensus("", "", "", "No extra charge", "Ask me if payment is required", true);

        assertFalse(session.consensusReady());
    }

    @Test
    public void partialConsensusCanBeShownBeforeClarificationCompletes() {
        CommunicationSession session = new CommunicationSession();
        CrewMateToolRegistry tools = new CrewMateToolRegistry(session, noOpListener());

        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("goal", "Ask whether late checkout is possible");
        args.put("ready", false);

        ToolResult result = execute(tools.registry(),
                call("partial", "update_task_consensus", args));

        assertTrue(result.success());
        assertEquals("", session.targetPerson());
        assertEquals("Ask whether late checkout is possible", session.goal());
        assertFalse(session.consensusReady());
    }

    @Test
    public void requestUserInputStoresQuestion() {
        CommunicationSession session = new CommunicationSession();
        CrewMateToolRegistry tools = new CrewMateToolRegistry(session, noOpListener());

        ToolResult result = execute(tools.registry(), call("ask", "request_user_input",
                map("question", "最多可以接受多少費用？")));

        assertTrue(result.success());
        assertEquals(CommunicationSession.Status.NEEDS_USER_INPUT, session.status());
        assertEquals("最多可以接受多少費用？", session.pendingUserQuestion());
    }

    @Test
    public void completeTaskStoresOutcomeAndCompletedState() {
        CommunicationSession session = new CommunicationSession();
        CrewMateToolRegistry tools = new CrewMateToolRegistry(session, noOpListener());
        Map<String, Object> consensus = new LinkedHashMap<String, Object>();
        consensus.put("target", "Reception");
        consensus.put("goal", "Ask where to put the laundry bag");
        consensus.put("constraints", "");
        consensus.put("escalation_boundary", "");
        consensus.put("ready", true);
        execute(tools.registry(), call("consensus", "update_task_consensus", consensus));

        ToolResult result = execute(tools.registry(), call("done", "complete_task",
                map("summary", "Reception confirmed the laundry bag should be left by the door.")));

        assertTrue(result.success());
        assertEquals(CommunicationSession.Status.COMPLETED, session.status());
        assertEquals("MATE", session.completionSource());
        assertEquals("Reception confirmed the laundry bag should be left by the door.", session.outcomeSummary());
    }

    @Test
    public void conversationMessagesKeepInsertionOrder() {
        CommunicationSession session = new CommunicationSession();
        session.addMessage(new Message("1", Message.Sender.USER, "MATE", "first", 1L, Message.Status.RECEIVED));
        session.addMessage(new Message("2", Message.Sender.MATE, "Reception", "second", 2L, Message.Status.INFO));
        session.addMessage(new Message("3", Message.Sender.OTHER_PERSON, "MATE", "third", 3L, Message.Status.RECEIVED));
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

    private static CrewMateToolRegistry.Listener noOpListener() {
        return new CrewMateToolRegistry.Listener() {
            @Override public void onSessionChanged(CommunicationSession session) {}
            @Override public void onUserInputRequested(CommunicationSession session, String question, String reason) {}
        };
    }

    private static ToolResult execute(ToolRegistry registry, ToolCall call) {
        final AtomicReference<ToolResult> result = new AtomicReference<ToolResult>();
        registry.execute(call, new ToolExecutor.Completion() {
            @Override public void complete(ToolResult value) { result.set(value); }
        });
        assertNotNull(result.get());
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
