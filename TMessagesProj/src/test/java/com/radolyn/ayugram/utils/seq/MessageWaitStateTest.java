package com.radolyn.ayugram.utils.seq;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MessageWaitStateTest {

    @Test
    public void baselineIdsAreNeverSelected() {
        MessageWaitState state = new MessageWaitState(Arrays.asList(-10, -11, null, -10));

        state.observeSnapshot(Arrays.asList(-10, -11));

        assertEquals(0, state.getSendingId());
        assertFalse(state.isComplete());
    }

    @Test
    public void firstNewPendingIdIsSelected() {
        MessageWaitState state = new MessageWaitState(Collections.singletonList(-10));

        state.observeSnapshot(Arrays.asList(-10, -20, -21));

        assertEquals(-20, state.getSendingId());
        assertFalse(state.isComplete());
    }

    @Test
    public void dispatchCompletionSnapshotSelectsNewCandidate() {
        MessageWaitState state = new MessageWaitState(Arrays.asList(-1, -2));

        state.observeSnapshot(Arrays.asList(-1, -2, -30));

        assertEquals(-30, state.getSendingId());
    }

    @Test
    public void laterQueueChangeSnapshotSelectsCandidate() {
        MessageWaitState state = new MessageWaitState(Collections.singletonList(-1));

        state.observeSnapshot(Collections.singletonList(-1));
        assertEquals(0, state.getSendingId());

        state.observeSnapshot(Arrays.asList(-1, -40));
        assertEquals(-40, state.getSendingId());
    }

    @Test
    public void snapshotOrderDeterminesFirstCandidate() {
        MessageWaitState state = new MessageWaitState(Collections.emptyList());

        state.observeSnapshot(Arrays.asList(-50, -51));

        assertEquals(-50, state.getSendingId());
    }

    @Test
    public void candidateAlreadySeenAsTerminalCompletesImmediately() {
        MessageWaitState state = new MessageWaitState(Collections.emptyList());
        state.observeTerminal(-60, false);

        state.observeSnapshot(Collections.singletonList(-60));

        assertEquals(-60, state.getSendingId());
        assertTrue(state.isComplete());
    }


    @Test
    public void matchingAckAfterIdentificationCompletes() {
        MessageWaitState state = new MessageWaitState(Collections.emptyList());
        state.observeSnapshot(Collections.singletonList(-70));

        state.observeTerminal(-70, false);

        assertTrue(state.isComplete());
    }

    @Test
    public void unrelatedAckAfterIdentificationDoesNotComplete() {
        MessageWaitState state = new MessageWaitState(Collections.emptyList());
        state.observeSnapshot(Collections.singletonList(-70));

        state.observeTerminal(-71, false);

        assertFalse(state.isComplete());
    }

    @Test
    public void sendErrorAlwaysMarksFailure() {
        MessageWaitState state = new MessageWaitState(Collections.emptyList());

        state.observeTerminal(-80, true);

        assertTrue(state.hasFailed());
        assertFalse(state.isComplete());
    }

    @Test
    public void matchingSendErrorCompletes() {
        MessageWaitState state = new MessageWaitState(Collections.emptyList());
        state.observeSnapshot(Collections.singletonList(-80));

        state.observeTerminal(-80, true);

        assertTrue(state.hasFailed());
        assertTrue(state.isComplete());
    }

    @Test
    public void unrelatedSendErrorAfterIdentificationMarksFailedWithoutCompleting() {
        MessageWaitState state = new MessageWaitState(Collections.emptyList());
        state.observeSnapshot(Collections.singletonList(-80));

        state.observeTerminal(-81, true);

        assertTrue(state.hasFailed());
        assertFalse(state.isComplete());
    }

    @Test
    public void earlyTerminalIsRetainedWithoutCompletingBeforeExpiry() {
        MessageWaitState state = new MessageWaitState(Collections.emptyList());

        state.observeTerminal(-90, false);

        assertEquals(0, state.getSendingId());
        assertFalse(state.isComplete());
    }


    @Test
    public void matchingServerDirectlySelectsNonBaselineId() {
        MessageWaitState state = new MessageWaitState(Collections.singletonList(-1));

        state.observeServer(-2, true);

        assertEquals(-2, state.getSendingId());
        assertTrue(state.isComplete());
    }

    @Test
    public void matchingServerCannotSelectBaselineId() {
        MessageWaitState state = new MessageWaitState(Collections.singletonList(-1));

        state.observeServer(-1, true);

        assertEquals(0, state.getSendingId());
        assertFalse(state.isComplete());
    }

    @Test
    public void otherDialogServerIsRetainedButNotSelected() {
        MessageWaitState state = new MessageWaitState(Collections.emptyList());

        state.observeServer(-3, false);

        assertEquals(0, state.getSendingId());
        assertFalse(state.isComplete());
    }

    @Test
    public void matchingDeletionBeforeIdentificationIsRetained() {
        MessageWaitState state = new MessageWaitState(Collections.emptyList());
        state.observeDeletion(Collections.singletonList(-4), true);

        state.observeSnapshot(Collections.singletonList(-4));

        assertEquals(-4, state.getSendingId());
        assertTrue(state.isComplete());
    }

    @Test
    public void selectedDeletionCompletes() {
        MessageWaitState state = new MessageWaitState(Collections.emptyList());
        state.observeSnapshot(Collections.singletonList(-5));

        state.observeDeletion(Collections.singletonList(-5), true);

        assertTrue(state.isComplete());
    }

    @Test
    public void unrelatedDeletionIsIgnored() {
        MessageWaitState state = new MessageWaitState(Collections.emptyList());
        state.observeSnapshot(Collections.singletonList(-5));

        state.observeDeletion(Collections.singletonList(-5), false);

        assertFalse(state.isComplete());
    }


    @Test
    public void candidateThenQueueReturnsToBaselineCompletes() {
        MessageWaitState state = new MessageWaitState(Collections.singletonList(-1));
        state.observeSnapshot(Arrays.asList(-1, -2));

        state.observeSnapshot(Collections.singletonList(-1));

        assertTrue(state.isComplete());
    }

    @Test
    public void candidateStillPresentButQueueCountAtBaselineCompletes() {
        MessageWaitState state = new MessageWaitState(Collections.singletonList(-1));
        state.observeSnapshot(Arrays.asList(-1, -2));

        state.observeSnapshot(Collections.singletonList(-2));

        assertTrue(state.isComplete());
    }

    @Test
    public void ambiguousTerminalBeforeExpiryDoesNotCompleteAtBaseline() {
        MessageWaitState state = new MessageWaitState(Collections.singletonList(-1));
        state.observeTerminal(-9, false);

        state.observeSnapshot(Collections.singletonList(-1));

        assertFalse(state.isComplete());
    }

    @Test
    public void expiryEnablesAmbiguousTerminalQueueDrain() {
        MessageWaitState state = new MessageWaitState(Collections.singletonList(-1));
        state.observeTerminal(-9, false);

        state.expireLookupWindow(Collections.singletonList(-1));

        assertTrue(state.isComplete());
    }

    @Test
    public void unrelatedEarlyErrorPreservesFailureAndCanReleaseAfterExpiry() {
        MessageWaitState state = new MessageWaitState(Collections.singletonList(-1));
        state.observeTerminal(-9, true);

        state.expireLookupWindow(Collections.singletonList(-1));

        assertTrue(state.hasFailed());
        assertTrue(state.isComplete());
    }

    @Test
    public void unrelatedEarlyAckCanReleaseOnlyAfterExpiry() {
        MessageWaitState state = new MessageWaitState(Collections.singletonList(-1));
        state.observeTerminal(-9, false);
        state.observeSnapshot(Collections.singletonList(-1));
        assertFalse(state.isComplete());

        state.expireLookupWindow(Collections.singletonList(-1));

        assertTrue(state.isComplete());
    }

    @Test
    public void expiryWithoutTerminalDoesNotComplete() {
        MessageWaitState state = new MessageWaitState(Collections.singletonList(-1));

        state.expireLookupWindow(Collections.singletonList(-1));

        assertFalse(state.isComplete());
    }

    @Test
    public void nullFinalSnapshotDoesNotReuseStaleQueueCount() {
        MessageWaitState state = new MessageWaitState(Collections.singletonList(-1));
        state.observeTerminal(-9, false);
        state.observeSnapshot(Collections.singletonList(-1));

        state.expireLookupWindow(null);

        assertFalse(state.isComplete());
        state.observeSnapshot(Collections.singletonList(-1));
        assertTrue(state.isComplete());
    }

    @Test
    public void multipleSignalsAreIdempotent() {
        MessageWaitState state = new MessageWaitState(Collections.emptyList());
        state.observeTerminal(-2, false);
        state.observeSnapshot(Collections.singletonList(-2));
        assertTrue(state.isComplete());

        state.observeTerminal(-3, true);
        state.observeServer(-4, true);
        state.observeDeletion(Collections.singletonList(-5), true);
        state.observeSnapshot(Collections.emptyList());

        assertEquals(-2, state.getSendingId());
        assertTrue(state.isComplete());
        assertTrue(state.hasFailed());
    }

    @Test
    public void otherDialogServerCanContributeToPostExpiryQueueDrainWithoutSelection() {
        MessageWaitState state = new MessageWaitState(Collections.singletonList(-1));
        state.observeServer(-9, false);

        state.expireLookupWindow(Collections.singletonList(-1));

        assertEquals(0, state.getSendingId());
        assertTrue(state.isComplete());
    }
}
