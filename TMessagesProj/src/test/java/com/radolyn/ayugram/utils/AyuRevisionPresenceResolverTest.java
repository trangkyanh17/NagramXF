package com.radolyn.ayugram.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

public class AyuRevisionPresenceResolverTest {
    @Test
    public void disabledFeatureDoesNotQuery() {
        assertIneligibleDoesNotQuery(false, true, 42L, 7L, false);
    }

    @Test
    public void missingFromPeerDoesNotQuery() {
        assertIneligibleDoesNotQuery(true, false, 42L, 7L, false);
    }

    @Test
    public void selfMessageDoesNotQuery() {
        assertIneligibleDoesNotQuery(true, true, 7L, 7L, false);
    }

    @Test
    public void expiredVoiceOrRoundDoesNotQuery() {
        assertIneligibleDoesNotQuery(true, true, 42L, 7L, true);
    }

    @Test
    public void eligibleQueryTrueIsReturned() {
        AtomicInteger queries = new AtomicInteger();
        boolean eligible = AyuRevisionPresenceResolver.isEligible(true, true, 42L, 7L, false);

        boolean result = AyuRevisionPresenceResolver.resolve(eligible, () -> {
            queries.incrementAndGet();
            return true;
        });

        assertTrue(result);
        assertEquals(1, queries.get());
    }

    @Test
    public void eligibleQueryFalseIsReturned() {
        AtomicInteger queries = new AtomicInteger();
        boolean eligible = AyuRevisionPresenceResolver.isEligible(true, true, 42L, 7L, false);

        boolean result = AyuRevisionPresenceResolver.resolve(eligible, () -> {
            queries.incrementAndGet();
            return false;
        });

        assertFalse(result);
        assertEquals(1, queries.get());
    }

    @Test
    public void newerTargetIdentityRejectsOlderCallback() {
        AyuAsyncRequestGate<AyuUiRequestKey> gate = new AyuAsyncRequestGate<>();
        Object firstTarget = new Object();
        Object secondTarget = new Object();
        AyuUiRequestKey firstKey = AyuUiRequestKey.forTarget(0, -100L, 10, firstTarget);
        AyuUiRequestKey secondKey = AyuUiRequestKey.forTarget(0, -100L, 10, secondTarget);
        long firstGeneration = gate.begin(firstKey);
        long secondGeneration = gate.begin(secondKey);

        assertFalse(gate.isCurrent(firstGeneration, firstKey));
        assertTrue(gate.isCurrent(secondGeneration, secondKey));
    }

    private static void assertIneligibleDoesNotQuery(boolean featureEnabled, boolean hasFromPeer,
                                                     long fromUserId, long selfUserId,
                                                     boolean expiredVoiceOrRound) {
        AtomicInteger queries = new AtomicInteger();
        boolean eligible = AyuRevisionPresenceResolver.isEligible(
                featureEnabled, hasFromPeer, fromUserId, selfUserId, expiredVoiceOrRound);

        boolean result = AyuRevisionPresenceResolver.resolve(eligible, () -> {
            queries.incrementAndGet();
            return true;
        });

        assertFalse(eligible);
        assertFalse(result);
        assertEquals(0, queries.get());
    }
}
