package com.radolyn.ayugram.utils.seq;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class MessageIdentificationWait {
    enum Result {
        SIGNALED,
        TIMED_OUT,
        INTERRUPTED
    }

    private MessageIdentificationWait() {
    }

    static Result await(CountDownLatch signal, long timeoutMs, Runnable onTimeout, Runnable onInterrupted) {
        try {
            if (signal.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return Result.SIGNALED;
            }
            onTimeout.run();
            return Result.TIMED_OUT;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            onInterrupted.run();
            return Result.INTERRUPTED;
        }
    }
}
