package com.crewpocket.mate.agent;

import com.crewpocket.mate.model.CommunicationSession;
import com.magic76.crew.agent.AgentEvent;
import com.magic76.crew.agent.ModelSession;
import com.magic76.crew.agent.SessionConfig;
import com.magic76.crew.agent.ToolResult;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CrewMateRuntimeStateTest {

    @Test
    public void completedTaskSurvivesVoiceRuntimeClose() {
        CommunicationSession session = new CommunicationSession();
        session.setStatus(CommunicationSession.Status.COMPLETED);

        CrewMateRuntime runtime = new CrewMateRuntime(session, new RecordingModelSession(), noOpListener());
        runtime.start();
        session.setStatus(CommunicationSession.Status.COMPLETED);
        runtime.close();

        assertEquals(CommunicationSession.Status.COMPLETED, session.status());
    }

    @Test
    public void needsUserInputSurvivesVoiceRuntimeStopEvent() {
        CommunicationSession session = new CommunicationSession();
        session.setStatus(CommunicationSession.Status.NEEDS_USER_INPUT);

        CrewMateRuntime runtime = new CrewMateRuntime(session, new RecordingModelSession(), noOpListener());
        runtime.start();
        runtime.onAgentEvent(AgentEvent.simple(AgentEvent.Type.STOPPED));

        assertEquals(CommunicationSession.Status.NEEDS_USER_INPUT, session.status());
    }

    private static CrewMateRuntime.Listener noOpListener() {
        return new CrewMateRuntime.Listener() {
            @Override public void onSessionChanged(CommunicationSession session) {}
            @Override public void onRuntimeStatus(String status) {}
        };
    }

    private static final class RecordingModelSession implements ModelSession {
        @Override public void start(SessionConfig config, Listener listener) {}
        @Override public void sendUserText(String text) {}
        @Override public void sendUserAudio(byte[] audio) {}
        @Override public void sendToolResult(ToolResult result) {}
        @Override public void interrupt() {}
        @Override public void close() {}
    }
}
