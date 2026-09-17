package com.crewpocket.mate.agent;

import com.crewpocket.mate.channel.FakeMessagingBackend;
import com.crewpocket.mate.model.CommunicationSession;
import com.crewpocket.mate.model.PendingApproval;
import com.crewpocket.mate.model.SpeechAudience;
import com.magic76.crew.agent.ToolCall;
import com.magic76.crew.agent.ToolExecutor;
import com.magic76.crew.agent.ToolResult;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SpeechAudienceBoundaryTest {

    @Test
    public void onlyPrivateModeRoutesMicrophoneToMate() {
        assertTrue(SpeechAudience.PRIVATE_TO_MATE.routesMicrophoneToMate());
        assertTrue(SpeechAudience.PRIVATE_TO_MATE.playsMateVoice());
        assertFalse(SpeechAudience.MATE_HANDLING.routesMicrophoneToMate());
        assertFalse(SpeechAudience.MATE_HANDLING.playsMateVoice());
        assertFalse(SpeechAudience.USER_DIRECT.routesMicrophoneToMate());
        assertFalse(SpeechAudience.USER_DIRECT.playsMateVoice());
    }

    @Test
    public void userDirectControlBlocksAutonomousSend() {
        FakeMessagingBackend backend = new FakeMessagingBackend(0L);
        CommunicationSession session = new CommunicationSession();
        CrewMateToolRegistry tools = new CrewMateToolRegistry(session, backend, noOpListener());

        execute(tools, call("find", "find_contact", map("query", "John", "goal", "Arrange dinner")));
        session.setDelegationAuthorized(true);
        execute(tools, call("draft", "draft_message", map("content", "Would 7 PM work?")));
        session.setUserDirectControl(true);

        ToolResult result = execute(tools, call("send", "send_message", map("content", "Would 7 PM work?")));
        assertFalse(result.success());
        assertEquals("USER_DIRECT_CONTROL", result.errorCode());
        assertEquals(0, backend.sendCount());
        backend.shutdown();
    }

    private static ToolResult execute(CrewMateToolRegistry tools, ToolCall call) {
        final AtomicReference<ToolResult> result = new AtomicReference<ToolResult>();
        tools.registry().execute(call, new ToolExecutor.Completion() {
            @Override public void complete(ToolResult value) { result.set(value); }
        });
        assertNotNull(result.get());
        return result.get();
    }

    private static CrewMateToolRegistry.Listener noOpListener() {
        return new CrewMateToolRegistry.Listener() {
            @Override public void onSessionChanged(CommunicationSession session) {}
            @Override public void onApprovalRequired(CommunicationSession session, PendingApproval approval) {}
            @Override public void onExternalReply(CommunicationSession session, com.crewpocket.mate.model.Message message) {}
            @Override public void onUserInputRequested(CommunicationSession session, String question, String reason) {}
        };
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
}
