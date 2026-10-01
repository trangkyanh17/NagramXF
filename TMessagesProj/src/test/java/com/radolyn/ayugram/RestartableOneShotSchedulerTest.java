package com.radolyn.ayugram;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class RestartableOneShotSchedulerTest {

    @Test
    public void firstTriggerSchedulesExactlyOneDelayedTask() {
        FakeScheduler backend = new FakeScheduler();
        RestartableOneShotScheduler scheduler = new RestartableOneShotScheduler(backend, 1500L);
        Runnable action = () -> { };

        scheduler.restart(action);

        assertEquals(1, backend.scheduled.size());
        assertEquals(1500L, backend.scheduled.get(0).delayMs);
        assertSame(action, backend.scheduled.get(0).action);
    }

    @Test
    public void pendingRestartCancelsWithoutInterruptAndReplaces() {
        FakeScheduler backend = new FakeScheduler();
        RestartableOneShotScheduler scheduler = new RestartableOneShotScheduler(backend, 1500L);

        scheduler.restart(() -> { });
        FakeHandle first = backend.scheduled.get(0).handle;
        scheduler.restart(() -> { });

        assertEquals(1, first.cancelCalls);
        assertFalse(first.lastMayInterruptIfRunning);
        assertEquals(2, backend.scheduled.size());
    }

    @Test
    public void executingOneShotDoesNotSelfReschedule() {
        FakeScheduler backend = new FakeScheduler();
        RestartableOneShotScheduler scheduler = new RestartableOneShotScheduler(backend, 1500L);

        scheduler.restart(() -> { });
        backend.scheduled.get(0).action.run();

        assertEquals(1, backend.scheduled.size());
    }

    @Test
    public void completedPriorHandleIsNotCancelled() {
        FakeScheduler backend = new FakeScheduler();
        RestartableOneShotScheduler scheduler = new RestartableOneShotScheduler(backend, 1500L);

        scheduler.restart(() -> { });
        FakeHandle first = backend.scheduled.get(0).handle;
        first.done = true;
        scheduler.restart(() -> { });

        assertEquals(0, first.cancelCalls);
        assertEquals(2, backend.scheduled.size());
    }

    @Test
    public void runningTaskCanOverlapReplacementWithoutInterrupt() {
        FakeScheduler backend = new FakeScheduler();
        RestartableOneShotScheduler scheduler = new RestartableOneShotScheduler(backend, 1500L);

        scheduler.restart(() -> { });
        FakeHandle first = backend.scheduled.get(0).handle;
        first.cancelResult = false;
        scheduler.restart(() -> { });

        assertEquals(1, first.cancelCalls);
        assertFalse(first.lastMayInterruptIfRunning);
        assertEquals(2, backend.scheduled.size());
    }

    @Test
    public void cancelFalseResultStillSchedulesReplacement() {
        FakeScheduler backend = new FakeScheduler();
        RestartableOneShotScheduler scheduler = new RestartableOneShotScheduler(backend, 1500L);

        scheduler.restart(() -> { });
        backend.scheduled.get(0).handle.cancelResult = false;
        scheduler.restart(() -> { });

        assertEquals(2, backend.scheduled.size());
    }

    @Test
    public void backendFailurePropagates() {
        FakeScheduler backend = new FakeScheduler();
        backend.failure = new IllegalStateException("boom");
        RestartableOneShotScheduler scheduler = new RestartableOneShotScheduler(backend, 1500L);

        try {
            scheduler.restart(() -> { });
            fail("expected backend failure");
        } catch (IllegalStateException e) {
            assertEquals("boom", e.getMessage());
        }
    }

    private static final class FakeScheduler implements RestartableOneShotScheduler.Scheduler {
        final List<Scheduled> scheduled = new ArrayList<>();
        RuntimeException failure;

        @Override
        public RestartableOneShotScheduler.Handle schedule(Runnable action, long delayMs) {
            if (failure != null) {
                throw failure;
            }
            FakeHandle handle = new FakeHandle();
            scheduled.add(new Scheduled(action, delayMs, handle));
            return handle;
        }
    }

    private static final class FakeHandle implements RestartableOneShotScheduler.Handle {
        boolean done;
        boolean cancelResult = true;
        int cancelCalls;
        boolean lastMayInterruptIfRunning;

        @Override
        public boolean isDone() {
            return done;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelCalls++;
            lastMayInterruptIfRunning = mayInterruptIfRunning;
            return cancelResult;
        }
    }

    private static final class Scheduled {
        final Runnable action;
        final long delayMs;
        final FakeHandle handle;

        Scheduled(Runnable action, long delayMs, FakeHandle handle) {
            this.action = action;
            this.delayMs = delayMs;
            this.handle = handle;
        }
    }
}
