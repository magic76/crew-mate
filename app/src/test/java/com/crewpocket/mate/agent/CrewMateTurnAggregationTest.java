package com.crewpocket.mate.agent;

import com.crewpocket.mate.channel.FakeMessagingBackend;
import com.crewpocket.mate.model.CommunicationSession;
import com.crewpocket.mate.model.Message;
import com.magic76.crew.agent.ModelEvent;
import com.magic76.crew.agent.ModelSession;
import com.magic76.crew.agent.SessionConfig;
import com.magic76.crew.agent.ToolResult;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CrewMateTurnAggregationTest {
    @Test
    public void modelTextDeltasBecomeOnePrivateMessageOnTurnComplete() {
        RecordingModelSession model = new RecordingModelSession();
        FakeMessagingBackend backend = new FakeMessagingBackend(0L);
        CommunicationSession session = new CommunicationSession();
        CrewMateRuntime runtime = new CrewMateRuntime(session, model, backend, null);
        runtime.start();

        model.emit(ModelEvent.text("好的"));
        model.emit(ModelEvent.text("我幫你"));
        model.emit(ModelEvent.text("問看看"));
        assertEquals(0, privateMateMessageCount(session));

        model.emit(ModelEvent.turnCompleted());
        assertEquals(1, privateMateMessageCount(session));
        assertEquals("好的我幫你問看看", lastPrivateMateMessage(session));

        runtime.close();
        backend.shutdown();
    }

    @Test
    public void interruptedModelTurnIsNotPersistedAsHalfMessage() {
        RecordingModelSession model = new RecordingModelSession();
        FakeMessagingBackend backend = new FakeMessagingBackend(0L);
        CommunicationSession session = new CommunicationSession();
        CrewMateRuntime runtime = new CrewMateRuntime(session, model, backend, null);
        runtime.start();

        model.emit(ModelEvent.text("This is an unfinished"));
        model.emit(ModelEvent.interrupted());
        model.emit(ModelEvent.turnCompleted());

        assertEquals(0, privateMateMessageCount(session));
        runtime.close();
        backend.shutdown();
    }

    private static int privateMateMessageCount(CommunicationSession session) {
        int count = 0;
        for (Message message : session.messages()) {
            if (message.sender == Message.Sender.MATE && "USER".equals(message.recipient)) count++;
        }
        return count;
    }

    private static String lastPrivateMateMessage(CommunicationSession session) {
        String value = "";
        for (Message message : session.messages()) {
            if (message.sender == Message.Sender.MATE && "USER".equals(message.recipient)) value = message.content();
        }
        return value;
    }

    private static final class RecordingModelSession implements ModelSession {
        ModelSession.Listener listener;

        @Override public void start(SessionConfig config, ModelSession.Listener listener) { this.listener = listener; }
        @Override public void sendUserText(String text) {}
        @Override public void sendUserAudio(byte[] audio) {}
        @Override public void sendToolResult(ToolResult result) {}
        @Override public void interrupt() {}
        @Override public void close() {}
        void emit(ModelEvent event) { listener.onModelEvent(event); }
    }
}
