package com.radolyn.ayugram;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;

final class AyuWorkerCoordinator {
    interface Scheduler {
        void reschedule(Runnable task);

        void shutdown();
    }

    private final AtomicBoolean[] needOffline;
    private final IntPredicate isAccountActivated;
    private final IntPredicate isOfflineModeEnabled;
    private final IntConsumer sendOffline;
    private final IntConsumer notifyFetch;
    private final Scheduler scheduler;

    AyuWorkerCoordinator(
            int accountCount,
            IntPredicate isAccountActivated,
            IntPredicate isOfflineModeEnabled,
            IntConsumer sendOffline,
            IntConsumer notifyFetch,
            Scheduler scheduler) {
        this.needOffline = new AtomicBoolean[accountCount];
        for (int i = 0; i < accountCount; i++) {
            needOffline[i] = new AtomicBoolean(false);
        }
        this.isAccountActivated = isAccountActivated;
        this.isOfflineModeEnabled = isOfflineModeEnabled;
        this.sendOffline = sendOffline;
        this.notifyFetch = notifyFetch;
        this.scheduler = scheduler;
    }

    void schedule() {
        scheduler.reschedule(this::runOnce);
    }

    void setOnline(int account, boolean needOffline) {
        AtomicBoolean flag = flagFor(account);
        if (flag == null) {
            return;
        }
        flag.set(needOffline);
        if (needOffline && isOfflineModeEnabled.test(account)) {
            schedule();
        }
    }

    void clearOnline(int account) {
        AtomicBoolean flag = flagFor(account);
        if (flag != null) {
            flag.set(false);
        }
    }

    void requestLastSeenUpdate(int account) {
        AtomicBoolean flag = flagFor(account);
        if (flag == null) {
            return;
        }
        if (isOfflineModeEnabled.test(account)) {
            flag.set(false);
            sendOffline.accept(account);
        }
        notifyFetch.accept(account);
    }

    void runOnce() {
        for (int account = 0; account < needOffline.length; account++) {
            if (!isAccountActivated.test(account)
                    || !isOfflineModeEnabled.test(account)
                    || !needOffline[account].getAndSet(false)) {
                continue;
            }
            sendOffline.accept(account);
            notifyFetch.accept(account);
        }
    }

    void shutdown() {
        scheduler.shutdown();
    }

    private AtomicBoolean flagFor(int account) {
        if (account < 0 || account >= needOffline.length) {
            return null;
        }
        return needOffline[account];
    }
}
