package com.radolyn.ayugram;

import com.radolyn.ayugram.controllers.AyuGhostController;
import com.radolyn.ayugram.utils.AyuGhostUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;

public class AyuWorker {
    private static final long INITIAL_DELAY_MS = 1500L;
    private static final long LAST_SEEN_FETCH_DELAY_MS = 100L;

    private static final AyuWorkerCoordinator coordinator = new AyuWorkerCoordinator(
            UserConfig.MAX_ACCOUNT_COUNT,
            account -> UserConfig.getInstance(account).isClientActivated(),
            account -> AyuGhostController.getInstance(account).isSendOfflinePacketAfterOnline(),
            AyuWorker::sendOffline,
            AyuWorker::notifyLastSeenPillFetch,
            new AyuOneShotScheduler(INITIAL_DELAY_MS));

    private AyuWorker() {
    }

    public static synchronized void run() {
        coordinator.schedule();
    }

    private static void sendOffline(int account) {
        AyuGhostUtils.performStatusRequest(account, true);
    }

    private static void notifyLastSeenPillFetch(int account) {
        AndroidUtilities.runOnUIThread(() ->
                NotificationCenter.getGlobalInstance().postNotificationName(
                        AyuConstants.LAST_SEEN_PILL_FETCH, account),
                LAST_SEEN_FETCH_DELAY_MS);
    }

    public static void requestLastSeenUpdate(int account) {
        coordinator.requestLastSeenUpdate(account);
    }

    public static synchronized void setOnline(int account, boolean needOffline) {
        coordinator.setOnline(account, needOffline);
    }

    public static void clearOnline(int account) {
        coordinator.clearOnline(account);
    }

    public static void shutdown() {
        coordinator.shutdown();
    }
}
