package com.crewpocket.mate.model;

/**
 * Defines who currently owns the user's speech path.
 * Only PRIVATE_TO_MATE routes microphone audio into Gemini Live.
 */
public enum SpeechAudience {
    IDLE(false, false),
    PRIVATE_TO_MATE(true, true),
    MATE_HANDLING(false, false),
    USER_DIRECT(false, false);

    private final boolean routesMicrophoneToMate;
    private final boolean playsMateVoice;

    SpeechAudience(boolean routesMicrophoneToMate, boolean playsMateVoice) {
        this.routesMicrophoneToMate = routesMicrophoneToMate;
        this.playsMateVoice = playsMateVoice;
    }

    public boolean routesMicrophoneToMate() { return routesMicrophoneToMate; }
    public boolean playsMateVoice() { return playsMateVoice; }
}
