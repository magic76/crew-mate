package com.crewpocket.mate.agent;

import com.crewpocket.mate.channel.FakeMessagingBackend;
import com.crewpocket.mate.channel.MessagingBackend;
import com.crewpocket.mate.model.CommunicationSession;
import com.crewpocket.mate.model.Message;
import com.crewpocket.mate.model.PendingApproval;
import com.crewpocket.mate.model.SpeechAudience;
import com.magic76.crew.agent.ModelEvent;
import com.magic76.crew.agent.ModelSession;
import com.magic76.crew.agent.SessionConfig;
import com.magic76.crew.agent.ToolCall;
import com.magic76.crew.agent.ToolExecutor;
import com.magic76.crew.agent.ToolResult;

import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SpeechAudienceBoundaryTest {

    @Test
    public void speechAudienceOwnsMicrophoneAndPlaybackPolicy() {
        assertTrue(SpeechAudience.PRIVATE_TO_MATE.routesMicrophoneToMate());
        assertTrue(SpeechAudience.PRIVATE_TO_MATE.playsMateVoice());

        assertTrue(SpeechAudience.EXTERNAL_WITH_MATE.routesMicrophoneToMate());
        assertTrue(SpeechAudience.EXTERNAL_WITH_MATE.playsMateVoice());

        assertFalse(SpeechAudience.MATE_HANDLING.routesMicrophoneToMate());
        assertFalse(SpeechAudience.MATE_HANDLING.playsMateVoice());

        assertFalse(SpeechAudience.USER_DIRECT.routesMicrophoneToMate());
        assertFalse(SpeechAudience.USER_DIRECT.playsMateVoice());
    }

    @Test
    public void typedPrivateBriefWorksWhileMicIsOff() {
        CommunicationSession session = new CommunicationSession();
        RecordingModelSession model = new RecordingModelSession();
        FakeMessagingBackend backend = new FakeMessagingBackend(0L);
        CrewMateRuntime runtime = new CrewMateRuntime(session, model, backend, noOpRuntimeListener());
        runtime.setSpeechAudience(SpeechAudience.MATE_HANDLING);
        runtime.start();

        runtime.submitPrivateText("幫我問能不能延後退房");

        Message message = onlyMessage(session.messages());
        assertEquals(Message.Sender.USER, message.sender);
        assertEquals("MATE", message.recipient);
        assertEquals("幫我問能不能延後退房", message.content());

        runtime.close();
        backend.shutdown();
    }

    @Test
    public void privateTranscriptRoutesUserToMate() {
        CommunicationSession session = new CommunicationSession();
        RecordingModelSession model = new RecordingModelSession();
        CrewMateRuntime runtime = new CrewMateRuntime(session, model, new FakeMessagingBackend(0L), noOpRuntimeListener());
        runtime.setSpeechAudience(SpeechAudience.PRIVATE_TO_MATE);

        runtime.recordUserTranscript("最多接受 500 泰銖");

        Message message = onlyMessage(session.messages());
        assertEquals(Message.Sender.USER, message.sender);
        assertEquals("MATE", message.recipient);
        assertEquals("最多接受 500 泰銖", message.content());
    }

    @Test
    public void externalTranscriptRoutesOtherPersonToMate() {
        CommunicationSession session = new CommunicationSession();
        session.setTarget("front-desk", "Front desk");
        RecordingModelSession model = new RecordingModelSession();
        CrewMateRuntime runtime = new CrewMateRuntime(session, model, new FakeMessagingBackend(0L), noOpRuntimeListener());
        runtime.setSpeechAudience(SpeechAudience.EXTERNAL_WITH_MATE);

        runtime.recordExternalSpeechTranscript("Late checkout is 500 baht.");

        Message message = onlyMessage(session.messages());
        assertEquals(Message.Sender.OTHER_PERSON, message.sender);
        assertEquals("MATE", message.recipient);
        assertEquals("Late checkout is 500 baht.", message.content());
    }

    @Test
    public void externalModelOutputRoutesMateToOtherPerson() {
        CommunicationSession session = new CommunicationSession();
        session.setTarget("front-desk", "Front desk");
        RecordingModelSession model = new RecordingModelSession();
        FakeMessagingBackend backend = new FakeMessagingBackend(0L);
        CrewMateRuntime runtime = new CrewMateRuntime(session, model, backend, noOpRuntimeListener());
        runtime.setSpeechAudience(SpeechAudience.EXTERNAL_WITH_MATE);
        runtime.start();

        model.emit(ModelEvent.text("Could you make it 300 baht?"));
        model.emit(ModelEvent.turnCompleted());

        Message message = onlyMessage(session.messages());
        assertEquals(Message.Sender.MATE, message.sender);
        assertEquals("Front desk", message.recipient);
        assertEquals("Could you make it 300 baht?", message.content());

        runtime.close();
        backend.shutdown();
    }

    @Test
    public void userDirectControlBlocksAutonomousSend() {
        FakeMessagingBackend backend = new FakeMessagingBackend(0L);
        CommunicationSession session = new CommunicationSession();
        CrewMateToolRegistry tools = new CrewMateToolRegistry(session, backend, noOpToolListener());

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

    @Test
    public void inPersonSendMessageNeverCallsProvider() {
        CountingInPersonBackend backend = new CountingInPersonBackend();
        CommunicationSession session = new CommunicationSession();
        CrewMateToolRegistry tools = new CrewMateToolRegistry(session, backend, noOpToolListener());

        execute(tools, call("find", "find_contact", map("query", "Front desk", "goal", "Ask for late checkout")));
        ToolResult result = execute(tools, call("send", "send_message", map("content", "Can we check out at 2 PM?")));

        assertFalse(result.success());
        assertEquals("IN_PERSON_LIVE_SPEECH", result.errorCode());
        assertEquals(0, backend.sendCount);
    }

    @Test
    public void firstDelegationIsAuthorizedOnlyAfterProviderAcceptsSend() {
        DeferredRemoteBackend backend = new DeferredRemoteBackend();
        CommunicationSession session = new CommunicationSession();
        CrewMateToolRegistry tools = new CrewMateToolRegistry(session, backend, noOpToolListener());

        execute(tools, call("find", "find_contact", map("query", "John", "goal", "Arrange dinner")));
        execute(tools, call("draft", "draft_message", map("content", "Would 7 PM work?")));

        final AtomicReference<ToolResult> sendResult = new AtomicReference<ToolResult>();
        tools.registry().execute(call("send", "send_message", map("content", "Would 7 PM work?")),
                new ToolExecutor.Completion() {
                    @Override public void complete(ToolResult value) { sendResult.set(value); }
                });

        assertNotNull(session.pendingApproval());
        assertFalse(session.delegationAuthorized());
        assertTrue(tools.approvePending(""));
        assertEquals(1, backend.sendCount);
        assertFalse(session.delegationAuthorized());
        assertEquals(null, sendResult.get());

        backend.deliver();
        assertTrue(session.delegationAuthorized());
        assertNotNull(sendResult.get());
        assertTrue(sendResult.get().success());
    }

    private static Message onlyMessage(List<Message> messages) {
        assertEquals(1, messages.size());
        return messages.get(0);
    }

    private static ToolResult execute(CrewMateToolRegistry tools, ToolCall call) {
        final AtomicReference<ToolResult> result = new AtomicReference<ToolResult>();
        tools.registry().execute(call, new ToolExecutor.Completion() {
            @Override public void complete(ToolResult value) { result.set(value); }
        });
        assertNotNull(result.get());
        return result.get();
    }

    private static CrewMateRuntime.Listener noOpRuntimeListener() {
        return new CrewMateRuntime.Listener() {
            @Override public void onSessionChanged(CommunicationSession session) {}
            @Override public void onApprovalRequired(CommunicationSession session, PendingApproval approval) {}
            @Override public void onRuntimeStatus(String status) {}
        };
    }

    private static CrewMateToolRegistry.Listener noOpToolListener() {
        return new CrewMateToolRegistry.Listener() {
            @Override public void onSessionChanged(CommunicationSession session) {}
            @Override public void onApprovalRequired(CommunicationSession session, PendingApproval approval) {}
            @Override public void onExternalReply(CommunicationSession session, Message message) {}
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

    private static final class RecordingModelSession implements ModelSession {
        private Listener listener;

        @Override public void start(SessionConfig config, Listener listener) { this.listener = listener; }
        @Override public void sendUserText(String text) {}
        @Override public void sendUserAudio(byte[] audio) {}
        @Override public void sendToolResult(ToolResult result) {}
        @Override public void interrupt() {}
        @Override public void close() {}

        void emit(ModelEvent event) {
            if (listener != null) listener.onModelEvent(event);
        }
    }

    private static final class CountingInPersonBackend implements MessagingBackend {
        int sendCount;

        @Override public ChannelMode channelMode() { return ChannelMode.IN_PERSON; }

        @Override public void findContact(String query, FindCallback callback) {
            callback.onFound(new Contact("front-desk", "Front desk"));
        }

        @Override public void getConversation(Contact contact, ConversationCallback callback) {
            callback.onLoaded(Collections.<RemoteMessage>emptyList());
        }

        @Override public void sendMessage(Contact contact, String content, SendCallback callback) {
            sendCount++;
            callback.onDelivered("should-not-happen");
        }
    }

    private static final class DeferredRemoteBackend implements MessagingBackend {
        int sendCount;
        SendCallback callback;

        @Override public void findContact(String query, FindCallback callback) {
            callback.onFound(new Contact("john", "John"));
        }

        @Override public void getConversation(Contact contact, ConversationCallback callback) {
            callback.onLoaded(Collections.<RemoteMessage>emptyList());
        }

        @Override public void sendMessage(Contact contact, String content, SendCallback callback) {
            sendCount++;
            this.callback = callback;
        }

        void deliver() {
            SendCallback target = callback;
            callback = null;
            if (target != null) target.onDelivered("provider-1");
        }
    }
}
