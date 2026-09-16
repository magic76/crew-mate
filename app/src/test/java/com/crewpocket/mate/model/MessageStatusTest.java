package com.crewpocket.mate.model;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class MessageStatusTest {
    @Test
    public void legacyDeliveredStatusIsPresentedAsSent() {
        Message message = new Message(Message.Sender.MATE, "John", "hello", Message.Status.DELIVERED);
        assertEquals(Message.Status.SENT, message.status());

        message.updateStatus(Message.Status.DELIVERED);
        assertEquals(Message.Status.SENT, message.status());
    }
}
