package com.crewpocket.mate.model;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TaskConsensusTest {

    @Test
    public void consensusIsNotReadyByDefault() {
        CommunicationSession session = new CommunicationSession();

        assertFalse(session.consensusReady());
    }

    @Test
    public void consensusStoresPurposeConstraintsAndEscalationBoundary() {
        CommunicationSession session = new CommunicationSession();

        session.setConsensus(
                "local-reception",
                "Reception",
                "Get late checkout until 2 PM",
                "Prefer free; accept up to 500 THB",
                "Ask the user before agreeing above 500 THB",
                true);

        assertTrue(session.consensusReady());
        assertEquals("Reception", session.targetPerson());
        assertEquals("Get late checkout until 2 PM", session.goal());
        assertEquals("Prefer free; accept up to 500 THB", session.constraints());
        assertEquals("Ask the user before agreeing above 500 THB", session.escalationBoundary());
    }

    @Test
    public void consensusReadyRequiresTargetAndGoal() {
        CommunicationSession session = new CommunicationSession();

        session.setConsensus("", "", "Get late checkout", "", "", true);
        assertFalse(session.consensusReady());

        session.setConsensus("local-reception", "Reception", "", "", "", true);
        assertFalse(session.consensusReady());
    }
}
