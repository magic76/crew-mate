package com.crewpocket.mate.agent;

import com.crewpocket.mate.channel.FakeMessagingBackend;
import com.crewpocket.mate.model.CommunicationSession;
import com.crewpocket.mate.model.PendingApproval;
import com.magic76.crew.agent.ModelEvent;
import com.magic76.crew.agent.ModelSession;
import com.magic76.crew.agent.SessionConfig;
import com.magic76.crew.agent.ToolResult;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CrewMateRuntimeStateTest {

    @Test
    public void waitingForReplySurvivesVoiceRuntimeClose() {
        CommunicationSession session = new CommunicationSession();
        session.setTarget("john", "John");
        session.setGoal("Arrange dinner tomorrow");
        session.setDelegationAuthorized(true);
        session.setStatus(CommunicationSession.Status.WAITING_FOR_REPLY);

        FakeMessagingBackend backend = new FakeMessagingBackend(5000L);
        RecordingModelSession model = new RecordingModelSession();
        CrewMateRuntime runtime = new CrewMateRuntime(session, model, backend, noOpListener());
        runtime.start();
        session.setStatus(CommunicationSession.Status.WAITING_FOR_REPLY);
        runtime.close();

        assertEquals(CommunicationSession.Status.WAITING_FOR_REPLY, session.status());
        backend.shutdown();
    }

    @Test
    public void completedTaskSurvivesVoiceRuntimeClose() {
        CommunicationSession session = new CommunicationSession();
        session.setStatus(CommunicationSession.Status.COMPLETED);

        FakeMessagingBackend backend = new FakeMessagingBackend(5000L);
        RecordingModelSession model = new RecordingModelSession();
        CrewMateRuntime runtime = new CrewMateRuntime(session, model, backend, noOpListener());
        runtime.start();
        session.setStatus(CommunicationSession.Status.COMPLETED);
        runtime.close();

        assertEquals(CommunicationSession.Status.COMPLETED, session.status());
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
        @Override public void start(SessionConfig config, Listener listener) {
            if (listener != null) listener.onModelEvent(ModelEvent.turnCompleted());
        }
        @Override public void sendUserText(String text) {}
        @Override public void sendUserAudio(byte[] audio) {}
        @Override public void sendToolResult(ToolResult result) {}
        @Override public void interrupt() {}
        @Override public void close() {}
    }
}
