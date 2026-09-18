package com.crewpocket.mate.model;

/** Connection lifecycle for the Gemini Live call, independent from who currently owns the microphone. */
public enum LiveCallState {
    OFF,
    CONNECTING,
    ACTIVE,
    ERROR
}
