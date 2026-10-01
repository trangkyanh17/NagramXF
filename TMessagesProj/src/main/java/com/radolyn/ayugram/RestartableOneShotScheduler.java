package com.radolyn.ayugram;

final class RestartableOneShotScheduler {

    interface Scheduler {
        Handle schedule(Runnable action, long delayMs);
    }

    interface Handle {
        boolean isDone();
        boolean cancel(boolean mayInterruptIfRunning);
    }

    private final Scheduler scheduler;
    private final long delayMs;
    private Handle currentHandle;

    RestartableOneShotScheduler(Scheduler scheduler, long delayMs) {
        this.scheduler = scheduler;
        this.delayMs = delayMs;
    }

    void restart(Runnable action) {
        Handle previous = currentHandle;
        if (previous != null && !previous.isDone()) {
            previous.cancel(false);
        }
        currentHandle = scheduler.schedule(action, delayMs);
    }
}
