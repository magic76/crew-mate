package com.crewpocket.mate.model;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CommunicationSessionLanguageTest {

    @Test
    public void languagesDefaultToAuto() {
        CommunicationSession session = new CommunicationSession();
        assertEquals("AUTO", session.userLanguage());
        assertEquals("AUTO", session.otherPersonLanguage());
    }

    @Test
    public void languagesCanBeSetPerTask() {
        CommunicationSession session = new CommunicationSession();

        session.setLanguages("zh-TW", "th-TH");

        assertEquals("zh-TW", session.userLanguage());
        assertEquals("th-TH", session.otherPersonLanguage());
    }

    @Test
    public void blankLanguageFallsBackToAuto() {
        CommunicationSession session = new CommunicationSession();

        session.setLanguages("", null);

        assertEquals("AUTO", session.userLanguage());
        assertEquals("AUTO", session.otherPersonLanguage());
    }
}
