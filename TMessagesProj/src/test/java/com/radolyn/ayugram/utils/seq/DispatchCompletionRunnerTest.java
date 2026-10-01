package com.radolyn.ayugram.utils.seq;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

public class DispatchCompletionRunnerTest {

    @Test
    public void completionRunsExactlyOnceAfterSuccessfulDispatch() {
        AtomicInteger order = new AtomicInteger();
        AtomicInteger dispatchOrder = new AtomicInteger();
        AtomicInteger completionOrder = new AtomicInteger();
        AtomicInteger completionCount = new AtomicInteger();

        DispatchCompletionRunner.run(
                () -> dispatchOrder.set(order.incrementAndGet()),
                () -> { completionCount.incrementAndGet(); completionOrder.set(order.incrementAndGet()); }
        );

        assertEquals(1, dispatchOrder.get());
        assertEquals(2, completionOrder.get());
        assertEquals(1, completionCount.get());
    }

    @Test
    public void completionRunsExactlyOnceAndOriginalRuntimeExceptionIsRethrown() {
        AtomicInteger completionCount = new AtomicInteger();
        RuntimeException expected = new IllegalStateException("dispatch failed");

        try {
            DispatchCompletionRunner.run(
                    () -> { throw expected; },
                    completionCount::incrementAndGet
            );
            fail("expected dispatch exception");
        } catch (RuntimeException actual) {
            assertSame(expected, actual);
        }

        assertEquals(1, completionCount.get());
    }
}
