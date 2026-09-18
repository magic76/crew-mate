package com.crewpocket.mate.voice;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GeminiLiveAudioGuardTest {

    @Test
    public void blocksMicFramesWhileMateIsSpeaking() {
        assertFalse(GeminiLiveModelSession.shouldSendCapturedAudio(true, 0L, 1000L));
    }

    @Test
    public void blocksMicFramesDuringPlaybackTailGuard() {
        assertFalse(GeminiLiveModelSession.shouldSendCapturedAudio(false, 1350L, 1200L));
    }

    @Test
    public void resumesMicAfterPlaybackTailGuard() {
        assertTrue(GeminiLiveModelSession.shouldSendCapturedAudio(false, 1350L, 1350L));
        assertTrue(GeminiLiveModelSession.shouldSendCapturedAudio(false, 1350L, 1500L));
    }
}
