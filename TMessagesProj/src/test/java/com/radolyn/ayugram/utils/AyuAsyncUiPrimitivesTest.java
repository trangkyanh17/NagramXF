package com.radolyn.ayugram.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

public class AyuAsyncUiPrimitivesTest {
    @Test
    public void latestRequestWinsForSameKey() {
        AyuAsyncRequestGate<AyuUiRequestKey> gate = new AyuAsyncRequestGate<>();
        AyuUiRequestKey key = AyuUiRequestKey.forMessage(0, -100L, 10);
        long first = gate.begin(key);
        long second = gate.begin(key);

        assertNotEquals(first, second);
        assertFalse(gate.isCurrent(first, key));
        assertTrue(gate.isCurrent(second, key));
    }

    @Test
    public void differentAccountIsDifferentKey() {
        assertNotEquals(AyuUiRequestKey.forMessage(0, -100L, 10),
                AyuUiRequestKey.forMessage(1, -100L, 10));
    }

    @Test
    public void differentMessageIsDifferentKey() {
        assertNotEquals(AyuUiRequestKey.forMessage(0, -100L, 10),
                AyuUiRequestKey.forMessage(0, -100L, 11));
    }

    @Test
    public void differentTargetIdentityIsDifferentKey() {
        Object firstTarget = new String("same-value");
        Object secondTarget = new String("same-value");
        AyuUiRequestKey first = AyuUiRequestKey.forTarget(0, -100L, 10, firstTarget);
        AyuUiRequestKey sameIdentity = AyuUiRequestKey.forTarget(0, -100L, 10, firstTarget);
        AyuUiRequestKey differentIdentity = AyuUiRequestKey.forTarget(0, -100L, 10, secondTarget);

        assertTrue(first.equals(sameIdentity));
        assertNotEquals(first, differentIdentity);
    }

    @Test
    public void invalidateRejectsPendingRequest() {
        AyuAsyncRequestGate<AyuUiRequestKey> gate = new AyuAsyncRequestGate<>();
        AyuUiRequestKey key = AyuUiRequestKey.forMessage(0, -100L, 10);
        long request = gate.begin(key);

        gate.invalidate();

        assertFalse(gate.isCurrent(request, key));
    }

    @Test
    public void failedLookupReturnsFallbackAndNextCallCanSucceed() {
        AtomicInteger errors = new AtomicInteger();

        String fallback = AyuSafeLookup.run(() -> {
            throw new IllegalStateException("boom");
        }, "fallback", error -> errors.incrementAndGet());
        String success = AyuSafeLookup.run(() -> "value", "fallback", error -> errors.incrementAndGet());

        assertEquals("fallback", fallback);
        assertEquals(1, errors.get());
        assertEquals("value", success);
    }
}
