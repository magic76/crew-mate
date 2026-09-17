package com.crewpocket.mate.agent;

import com.crewpocket.mate.channel.FakeMessagingBackend;
import com.crewpocket.mate.channel.MessagingBackend;
import com.crewpocket.mate.model.CommunicationSession;
import com.crewpocket.mate.model.Message;
import com.crewpocket.mate.model.PendingApproval;
import com.magic76.crew.agent.ModelSession;
import com.magic76.crew.agent.SessionConfig;
import com.magic76.crew.agent.ToolResult;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ExternalReplyResumeTest {

    @Test
    public void watchedReplyFeedsSameHarnessAndVisibleConversation() {
        CommunicationSession session = new CommunicationSession();
        session.setTarget("john", "John");
        session.setGoal("Arrange dinner tomorrow");
        session.setDelegationAuthorized(true);
        session.setStatus(CommunicationSession.Status.WAITING_FOR_REPLY);

        RecordingModelSession model = new RecordingModelSession();
        FakeMessagingBackend backend = new FakeMessagingBackend(5000L);
        CrewMateRuntime runtime = new CrewMateRuntime(session, model, backend, noOpListener());
        runtime.start();

        runtime.acceptExternalReply(new MessagingBackend.RemoteMessage(
                "remote-1", "John", "7 PM works for me", 100L, false));

        assertEquals(CommunicationSession.Status.THINKING, session.status());
        assertEquals(1, session.messages().size());
        Message visible = session.messages().get(0);
        assertEquals(Message.Sender.OTHER_PERSON, visible.sender);
        assertEquals("7 PM works for me", visible.content());
        assertTrue(model.userTexts.get(model.userTexts.size() - 1).contains("EXTERNAL_MESSAGE from John"));
        assertTrue(model.userTexts.get(model.userTexts.size() - 1).contains("7 PM works for me"));

        runtime.acceptExternalReply(new MessagingBackend.RemoteMessage(
                "remote-1", "John", "7 PM works for me", 100L, false));
        assertEquals(1, session.messages().size());

        runtime.close();
        backend.shutdown();
    }

    private static CrewMateRuntime.Listener noOpListener() {
        return new CrewMateRuntime.Listener() {
            @Override public void onSessionChanged(CommunicationSession session) {}
            @Override public void onApprovalRequired(CommunicationSession session, PendingApproval approval) {}
            @Override public void onRuntimeStatus(String status) {}
        };
    }

    private static final class RecordingModelSession implements ModelSession {
        final List<String> userTexts = new ArrayList<String>();
        @Override public void start(SessionConfig config, Listener listener) {}
        @Override public void sendUserText(String text) { userTexts.add(text); }
        @Override public void sendUserAudio(byte[] audio) {}
        @Override public void sendToolResult(ToolResult result) {}
        @Override public void interrupt() {}
        @Override public void close() {}
    }
}
