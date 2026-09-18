package com.crewpocket.mate.model;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class MessageTranslationTest {

    @Test
    public void translationMetadataDoesNotReplaceOriginalConversationText() {
        Message message = new Message(
                Message.Sender.OTHER_PERSON,
                "MATE",
                "Xin chào",
                Message.Status.RECEIVED);

        message.setTranslation("你好", "vi-VN", "zh-TW");

        assertEquals("Xin chào", message.content());
        assertEquals("你好", message.translatedText());
        assertEquals("vi-VN", message.originalLanguage());
        assertEquals("zh-TW", message.translatedLanguage());
    }
}
