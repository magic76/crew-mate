package com.crewpocket.mate.model;

/**
 * Defines who owns the live speech path.
 * PRIVATE_TO_MATE is private user instruction.
 * EXTERNAL_WITH_MATE is the other person speaking directly with Mate on this device.
 */
public enum SpeechAudience {
    IDLE(false, false, false, false),
    PRIVATE_TO_MATE(true, true, true, false),
    EXTERNAL_WITH_MATE(true, true, false, true),
    MATE_HANDLING(false, false, false, false),
    USER_DIRECT(false, false, false, false);

    private final boolean routesMicrophoneToMate;
    private final boolean playsMateVoice;
    private final boolean privateUserSpeech;
    private final boolean externalPersonSpeech;

    SpeechAudience(boolean routesMicrophoneToMate, boolean playsMateVoice,
                   boolean privateUserSpeech, boolean externalPersonSpeech) {
        this.routesMicrophoneToMate = routesMicrophoneToMate;
        this.playsMateVoice = playsMateVoice;
        this.privateUserSpeech = privateUserSpeech;
        this.externalPersonSpeech = externalPersonSpeech;
    }

    public boolean routesMicrophoneToMate() { return routesMicrophoneToMate; }
    public boolean playsMateVoice() { return playsMateVoice; }
    public boolean isPrivateUserSpeech() { return privateUserSpeech; }
    public boolean isExternalPersonSpeech() { return externalPersonSpeech; }
}
