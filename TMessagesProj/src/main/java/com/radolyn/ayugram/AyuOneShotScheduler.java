package com.radolyn.ayugram;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class AyuOneShotScheduler implements AyuWorkerCoordinator.Scheduler {
    private final long delayMs;
    private final ScheduledExecutorService executor;
    private ScheduledFuture<?> pending;

    AyuOneShotScheduler(long delayMs) {
        this.delayMs = delayMs;
        this.executor = Executors.newSingleThreadScheduledExecutor();
    }

    @Override
    public synchronized void reschedule(Runnable task) {
        if (pending != null && !pending.isDone()) {
            pending.cancel(false);
        }
        pending = executor.schedule(task, delayMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void shutdown() {
        if (pending != null && !pending.isDone()) {
            pending.cancel(false);
        }
        pending = null;
        executor.shutdown();
    }
}
