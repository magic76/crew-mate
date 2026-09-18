package com.crewpocket.mate.agent;

import com.crewpocket.mate.channel.InPersonMessagingBackend;
import com.crewpocket.mate.model.CommunicationSession;
import com.magic76.crew.agent.ModelEvent;
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

        CrewMateRuntime runtime = new CrewMateRuntime(
                session, new RecordingModelSession(), new InPersonMessagingBackend(), noOpListener());
        runtime.start();
        session.setStatus(CommunicationSession.Status.COMPLETED);
        runtime.close();

        assertEquals(CommunicationSession.Status.COMPLETED, session.status());
    }

    @Test
    public void needsUserInputSurvivesVoiceRuntimeStopEvent() {
        CommunicationSession session = new CommunicationSession();
        session.setStatus(CommunicationSession.Status.NEEDS_USER_INPUT);

        RecordingModelSession model = new RecordingModelSession();
        CrewMateRuntime runtime = new CrewMateRuntime(
                session, model, new InPersonMessagingBackend(), noOpListener());
        runtime.start();
        model.emit(ModelEvent.simple(ModelEvent.Type.STOPPED));

        assertEquals(CommunicationSession.Status.NEEDS_USER_INPUT, session.status());
    }

    private static CrewMateRuntime.Listener noOpListener() {
        return new CrewMateRuntime.Listener() {
            @Override public void onSessionChanged(CommunicationSession session) {}
            @Override public void onRuntimeStatus(String status) {}
        };
    }

    private static final class RecordingModelSession implements ModelSession {
        private Listener listener;
        @Override public void start(SessionConfig config, Listener listener) { this.listener = listener; }
        @Override public void sendUserText(String text) {}
        @Override public void sendUserAudio(byte[] audio) {}
        @Override public void sendToolResult(ToolResult result) {}
        @Override public void interrupt() {}
        @Override public void close() {}
        void emit(ModelEvent event) { if (listener != null) listener.onModelEvent(event); }
    }
}
