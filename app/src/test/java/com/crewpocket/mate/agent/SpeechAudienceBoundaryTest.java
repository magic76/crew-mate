package com.crewpocket.mate.agent;

import com.crewpocket.mate.model.CommunicationSession;
import com.crewpocket.mate.model.Message;
import com.crewpocket.mate.model.SpeechAudience;
import com.magic76.crew.agent.ModelEvent;
import com.magic76.crew.agent.ModelSession;
import com.magic76.crew.agent.SessionConfig;
import com.magic76.crew.agent.ToolResult;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
        CrewMateRuntime runtime = new CrewMateRuntime(session, model, noOpRuntimeListener());
        runtime.setSpeechAudience(SpeechAudience.MATE_HANDLING);
        runtime.start();

        runtime.submitPrivateText("幫我問能不能延後退房");

        Message message = onlyMessage(session.messages());
        assertEquals(Message.Sender.USER, message.sender);
        assertEquals("MATE", message.recipient);
        assertEquals("幫我問能不能延後退房", message.content());
        runtime.close();
    }

    @Test
    public void finalizeTaskBriefSendsDeterministicProductEvent() {
        CommunicationSession session = new CommunicationSession();
        RecordingModelSession model = new RecordingModelSession();
        CrewMateRuntime runtime = new CrewMateRuntime(session, model, noOpRuntimeListener());
        runtime.start();

        runtime.finalizeTaskBrief();

        assertTrue(model.lastUserText.contains("PRODUCT_EVENT=FINALIZE_TASK_CONSENSUS"));
        runtime.close();
    }

    @Test
    public void finalizeTaskBriefFallsBackToQuestionInsteadOfStalling() {
        CommunicationSession session = new CommunicationSession();
        RecordingModelSession model = new RecordingModelSession();
        CrewMateRuntime runtime = new CrewMateRuntime(session, model, noOpRuntimeListener());
        runtime.start();

        runtime.finalizeTaskBrief();
        model.emit(ModelEvent.turnCompleted());
        assertTrue(model.lastUserText.contains("PRODUCT_EVENT=FINALIZE_TASK_CONSENSUS_RETRY"));

        model.emit(ModelEvent.turnCompleted());

        assertEquals(CommunicationSession.Status.NEEDS_USER_INPUT, session.status());
        assertFalse(session.pendingUserQuestion().isEmpty());
        assertFalse(session.consensusReady());
        runtime.close();
    }

    @Test
    public void oneShotSupplementWaitsForCurrentAudioTurnBeforeForcedFinalize() {
        CommunicationSession session = new CommunicationSession();
        session.setConsensus(
                "local-front-desk",
                "Front desk",
                "Ask for late checkout",
                "",
                "Ask before changing the agreed terms",
                true);
        RecordingModelSession model = new RecordingModelSession();
        CrewMateRuntime runtime = new CrewMateRuntime(session, model, noOpRuntimeListener());
        runtime.setSpeechAudience(SpeechAudience.PRIVATE_TO_MATE);
        runtime.start();

        runtime.recordUserTranscript("Also ask whether breakfast can be included.");
        runtime.finalizePrivateSupplementAfterCurrentTurn();

        assertEquals("", model.lastUserText);

        model.emit(ModelEvent.turnCompleted());

        assertTrue(model.lastUserText.contains("PRODUCT_EVENT=FINALIZE_TASK_CONSENSUS"));
        runtime.close();
    }

    @Test
    public void privateTextInvalidatesPreviouslyReadyConsensus() {
        CommunicationSession session = new CommunicationSession();
        session.setConsensus(
                "local-front-desk",
                "Front desk",
                "Ask for late checkout",
                "Up to 500 THB",
                "Ask before agreeing above 500 THB",
                true);
        CrewMateRuntime runtime = new CrewMateRuntime(
                session, new RecordingModelSession(), noOpRuntimeListener());
        runtime.start();

        runtime.submitPrivateText("Actually, I only want to pay up to 300 baht.");

        assertFalse(session.consensusReady());
        runtime.close();
    }

    @Test
    public void privateVoiceTranscriptInvalidatesPreviouslyReadyConsensus() {
        CommunicationSession session = new CommunicationSession();
        session.setConsensus(
                "local-front-desk",
                "Front desk",
                "Ask for late checkout",
                "Up to 500 THB",
                "Ask before agreeing above 500 THB",
                true);
        CrewMateRuntime runtime = new CrewMateRuntime(
                session, new RecordingModelSession(), noOpRuntimeListener());
        runtime.setSpeechAudience(SpeechAudience.PRIVATE_TO_MATE);

        runtime.recordUserTranscript("Change the limit to 300 baht.");

        assertFalse(session.consensusReady());
    }

    @Test
    public void privateTranscriptRoutesUserToMate() {
        CommunicationSession session = new CommunicationSession();
        CrewMateRuntime runtime = new CrewMateRuntime(session, new RecordingModelSession(), noOpRuntimeListener());
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
        CrewMateRuntime runtime = new CrewMateRuntime(session, new RecordingModelSession(), noOpRuntimeListener());
        runtime.setSpeechAudience(SpeechAudience.EXTERNAL_WITH_MATE);

        runtime.recordExternalSpeechTranscript("Late checkout is 500 baht.");

        Message message = onlyMessage(session.messages());
        assertEquals(Message.Sender.OTHER_PERSON, message.sender);
        assertEquals("MATE", message.recipient);
        assertEquals("Late checkout is 500 baht.", message.content());
    }

    @Test
    public void externalSpeechThatSoundsLikeAnInstructionStillBelongsToOtherPerson() {
        CommunicationSession session = new CommunicationSession();
        session.setTarget("front-desk", "Front desk");
        CrewMateRuntime runtime = new CrewMateRuntime(
                session, new RecordingModelSession(), noOpRuntimeListener());
        runtime.setSpeechAudience(SpeechAudience.EXTERNAL_WITH_MATE);

        runtime.recordExternalSpeechTranscript("You can accept 1000 baht if you want.");

        Message message = onlyMessage(session.messages());
        assertEquals(Message.Sender.OTHER_PERSON, message.sender);
        assertEquals("MATE", message.recipient);
        assertEquals("You can accept 1000 baht if you want.", message.content());
    }

    @Test
    public void externalModelOutputWithoutRealExternalSpeechIsDiscarded() {
        CommunicationSession session = new CommunicationSession();
        session.setTarget("front-desk", "Front desk");
        RecordingModelSession model = new RecordingModelSession();
        CrewMateRuntime runtime = new CrewMateRuntime(session, model, noOpRuntimeListener());
        runtime.setSpeechAudience(SpeechAudience.EXTERNAL_WITH_MATE);
        runtime.start();

        model.emit(ModelEvent.text("The front desk says late checkout costs 500 baht."));
        model.emit(ModelEvent.turnCompleted());

        assertTrue(session.messages().isEmpty());
        runtime.close();
    }

    @Test
    public void externalModelOutputRoutesMateOnlyAfterOtherPersonActuallySpeaks() {
        CommunicationSession session = new CommunicationSession();
        session.setTarget("front-desk", "Front desk");
        RecordingModelSession model = new RecordingModelSession();
        CrewMateRuntime runtime = new CrewMateRuntime(session, model, noOpRuntimeListener());
        runtime.setSpeechAudience(SpeechAudience.EXTERNAL_WITH_MATE);
        runtime.start();

        runtime.recordExternalSpeechTranscript("Late checkout is 500 baht.");
        model.emit(ModelEvent.text("Could you make it 300 baht?"));
        model.emit(ModelEvent.turnCompleted());

        List<Message> messages = session.messages();
        assertEquals(2, messages.size());
        assertEquals(Message.Sender.OTHER_PERSON, messages.get(0).sender);
        assertEquals("Late checkout is 500 baht.", messages.get(0).content());
        assertEquals(Message.Sender.MATE, messages.get(1).sender);
        assertEquals("Front desk", messages.get(1).recipient);
        assertEquals("Could you make it 300 baht?", messages.get(1).content());
        runtime.close();
    }

    @Test
    public void reenteringExternalModeRequiresFreshExternalSpeech() {
        CommunicationSession session = new CommunicationSession();
        session.setTarget("front-desk", "Front desk");
        RecordingModelSession model = new RecordingModelSession();
        CrewMateRuntime runtime = new CrewMateRuntime(session, model, noOpRuntimeListener());
        runtime.setSpeechAudience(SpeechAudience.EXTERNAL_WITH_MATE);
        runtime.start();

        runtime.recordExternalSpeechTranscript("500 baht.");
        model.emit(ModelEvent.text("I will check with the guest."));
        model.emit(ModelEvent.turnCompleted());

        runtime.setSpeechAudience(SpeechAudience.PRIVATE_TO_MATE);
        runtime.setSpeechAudience(SpeechAudience.EXTERNAL_WITH_MATE);
        model.emit(ModelEvent.text("The front desk now says 300 baht."));
        model.emit(ModelEvent.turnCompleted());

        assertEquals(2, session.messages().size());
        runtime.close();
    }

    @Test
    public void userDirectControlPreventsPrivateInput() {
        CommunicationSession session = new CommunicationSession();
        CrewMateRuntime runtime = new CrewMateRuntime(session, new RecordingModelSession(), noOpRuntimeListener());
        session.setUserDirectControl(true);
        runtime.setSpeechAudience(SpeechAudience.USER_DIRECT);

        runtime.submitPrivateText("This should not be accepted");
        runtime.recordUserTranscript("Neither should this");

        assertTrue(session.messages().isEmpty());
    }

    private static Message onlyMessage(List<Message> messages) {
        assertEquals(1, messages.size());
        return messages.get(0);
    }

    private static CrewMateRuntime.Listener noOpRuntimeListener() {
        return new CrewMateRuntime.Listener() {
            @Override public void onSessionChanged(CommunicationSession session) {}
            @Override public void onRuntimeStatus(String status) {}
        };
    }

    private static final class RecordingModelSession implements ModelSession {
        private Listener listener;
        private String lastUserText = "";

        @Override public void start(SessionConfig config, Listener listener) { this.listener = listener; }
        @Override public void sendUserText(String text) { lastUserText = text == null ? "" : text; }
        @Override public void sendUserAudio(byte[] audio) {}
        @Override public void sendToolResult(ToolResult result) {}
        @Override public void interrupt() {}
        @Override public void close() {}

        void emit(ModelEvent event) {
            if (listener != null) listener.onModelEvent(event);
        }
    }
}
