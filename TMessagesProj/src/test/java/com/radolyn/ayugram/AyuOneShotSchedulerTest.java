package com.radolyn.ayugram;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class AyuOneShotSchedulerTest {
    @Test
    public void scheduledActionExecutesExactlyOnce() throws Exception {
        AyuOneShotScheduler scheduler = new AyuOneShotScheduler(40L);
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch ran = new CountDownLatch(1);
        try {
            scheduler.reschedule(() -> {
                executions.incrementAndGet();
                ran.countDown();
            });

            assertTrue(ran.await(2, TimeUnit.SECONDS));
            Thread.sleep(120L);
            assertEquals(1, executions.get());
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    public void reschedulingReplacesEarlierPendingAction() throws Exception {
        AyuOneShotScheduler scheduler = new AyuOneShotScheduler(120L);
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch secondRan = new CountDownLatch(1);
        try {
            scheduler.reschedule(() -> executions.addAndGet(10));
            Thread.sleep(30L);
            scheduler.reschedule(() -> {
                executions.incrementAndGet();
                secondRan.countDown();
            });

            assertTrue(secondRan.await(2, TimeUnit.SECONDS));
            Thread.sleep(150L);
            assertEquals(1, executions.get());
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    public void shutdownCancelsPendingAction() throws Exception {
        AyuOneShotScheduler scheduler = new AyuOneShotScheduler(150L);
        AtomicInteger executions = new AtomicInteger();
        scheduler.reschedule(executions::incrementAndGet);
        scheduler.shutdown();

        Thread.sleep(250L);
        assertEquals(0, executions.get());
    }
}
