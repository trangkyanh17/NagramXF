package com.radolyn.ayugram.utils.seq;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MessageIdentificationWaitTest {

    @Test
    public void preSignalledLatchReturnsSignalledWithoutCallbacks() {
        CountDownLatch signal = new CountDownLatch(1);
        signal.countDown();
        AtomicInteger timedOut = new AtomicInteger();
        AtomicInteger interrupted = new AtomicInteger();

        MessageIdentificationWait.Result result = MessageIdentificationWait.await(
                signal, 1000L, timedOut::incrementAndGet, interrupted::incrementAndGet);

        assertEquals(MessageIdentificationWait.Result.SIGNALED, result);
        assertEquals(0, timedOut.get());
        assertEquals(0, interrupted.get());
    }

    @Test
    public void zeroTimeoutRunsTimeoutCallbackOnce() {
        AtomicInteger timedOut = new AtomicInteger();
        AtomicInteger interrupted = new AtomicInteger();

        MessageIdentificationWait.Result result = MessageIdentificationWait.await(
                new CountDownLatch(1), 0L, timedOut::incrementAndGet, interrupted::incrementAndGet);

        assertEquals(MessageIdentificationWait.Result.TIMED_OUT, result);
        assertEquals(1, timedOut.get());
        assertEquals(0, interrupted.get());
    }

    @Test
    public void interruptedWaitRestoresInterruptFlagAndRunsInterruptCallbackOnce() {
        AtomicInteger timedOut = new AtomicInteger();
        AtomicInteger interrupted = new AtomicInteger();
        Thread.currentThread().interrupt();
        try {
            MessageIdentificationWait.Result result = MessageIdentificationWait.await(
                    new CountDownLatch(1), 1000L, timedOut::incrementAndGet, interrupted::incrementAndGet);

            assertEquals(MessageIdentificationWait.Result.INTERRUPTED, result);
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, timedOut.get());
            assertEquals(1, interrupted.get());
        } finally {
            Thread.interrupted();
        }
    }
}
