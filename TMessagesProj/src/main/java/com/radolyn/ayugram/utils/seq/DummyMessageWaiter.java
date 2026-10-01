package com.radolyn.ayugram.utils.seq;

import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;

public class DummyMessageWaiter extends SyncWaiter {

    private static final long LOOKUP_TIMEOUT_MS = 3500L;

    private final Object stateLock = new Object();
    private final CountDownLatch identificationSignal = new CountDownLatch(1);
    private final ArrayList<StateMutation> pendingMutations = new ArrayList<>();

    private MessageWaitState state;
    private long dialogId;

    public volatile int sendingId;

    @FunctionalInterface
    private interface StateMutation {
        void apply(MessageWaitState state);
    }

    public DummyMessageWaiter(int currentAccount) {
        super(currentAccount);
        notifications.add(NotificationCenter.messageReceivedByServer);
        notifications.add(NotificationCenter.messageSendError);
        notifications.add(NotificationCenter.messageReceivedByAck);
        notifications.add(NotificationCenter.messagesDeleted);
        notifications.add(NotificationCenter.sendingMessagesChanged);
    }

    public void prepare(long dialogId, ArrayList<Integer> existingIds) {
        if (dialogId == 0) {
            dialogId = UserConfig.getInstance(currentAccount).getClientUserId();
        }
        boolean signalIdentification = false;
        boolean releaseWaiter = false;
        synchronized (stateLock) {
            this.dialogId = dialogId;
            state = new MessageWaitState(existingIds);
            for (int i = 0; i < pendingMutations.size(); i++) {
                int previousId = state.getSendingId();
                pendingMutations.get(i).apply(state);
                signalIdentification |= previousId == 0 && state.getSendingId() != 0;
            }
            pendingMutations.clear();
            sendingId = state.getSendingId();
            releaseWaiter = state.isComplete();
        }
        finishStateChange(signalIdentification, releaseWaiter);
    }

    public void onDispatchCompleted() {
        reconcileQueue();
    }

    public int awaitSendingId() {
        MessageIdentificationWait.await(
                identificationSignal,
                LOOKUP_TIMEOUT_MS,
                this::expireLookupWindow,
                this::unsubscribe
        );
        return sendingId;
    }

    public void trySetSendingId(long dialogId, ArrayList<Integer> existingIds) {
        prepare(dialogId, existingIds);
        onDispatchCompleted();
        awaitSendingId();
    }

    public boolean hasFailed() {
        synchronized (stateLock) {
            return state != null && state.hasFailed() || isTimedOut();
        }
    }

    private void expireLookupWindow() {
        ArrayList<Integer> finalSnapshot = snapshotQueue();
        applyStateMutation(current -> current.expireLookupWindow(finalSnapshot));
    }

    private void reconcileQueue() {
        ArrayList<Integer> snapshot = snapshotQueue();
        if (snapshot != null) {
            applyStateMutation(current -> current.observeSnapshot(snapshot));
        }
    }

    private ArrayList<Integer> snapshotQueue() {
        long targetDialog;
        synchronized (stateLock) {
            if (state == null) {
                return null;
            }
            targetDialog = dialogId;
        }
        try {
            return SendMessagesHelper.getInstance(currentAccount).getSendingMessageIds(targetDialog);
        } catch (Exception ignore) {
            return null;
        }
    }

    private void applyStateMutation(StateMutation mutation) {
        boolean signalIdentification = false;
        boolean releaseWaiter = false;
        synchronized (stateLock) {
            if (state == null) {
                pendingMutations.add(mutation);
                return;
            }
            int previousId = state.getSendingId();
            mutation.apply(state);
            sendingId = state.getSendingId();
            signalIdentification = previousId == 0 && sendingId != 0;
            releaseWaiter = state.isComplete();
        }
        finishStateChange(signalIdentification, releaseWaiter);
    }

    private void finishStateChange(boolean signalIdentification, boolean releaseWaiter) {
        if (signalIdentification) {
            identificationSignal.countDown();
        }
        if (releaseWaiter) {
            unsubscribe();
        }
    }

    private boolean isPreparedAndUnidentified() {
        synchronized (stateLock) {
            return state != null && state.getSendingId() == 0 && !state.isComplete();
        }
    }

    private boolean matchesDialog(long eventDialogId) {
        return Math.abs(eventDialogId) == Math.abs(dialogId)
                || eventDialogId == 0L
                || dialogId == 0L;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.sendingMessagesChanged) {
            reconcileQueue();
            return;
        }

        if (id == NotificationCenter.messageReceivedByAck || id == NotificationCenter.messageSendError) {
            handleTerminalEvent(id, args);
            return;
        }

        if (id == NotificationCenter.messageReceivedByServer) {
            handleServerEvent(args);
            return;
        }

        if (id == NotificationCenter.messagesDeleted) {
            handleDeletionEvent(args);
        }
    }

    private void handleTerminalEvent(int id, Object... args) {
        if (args == null || args.length == 0 || !(args[0] instanceof Integer)) {
            return;
        }
        int messageId = (Integer) args[0];
        boolean failure = id == NotificationCenter.messageSendError;
        applyStateMutation(current -> current.observeTerminal(messageId, failure));
        if (isPreparedAndUnidentified()) {
            reconcileQueue();
        }
    }

    private void handleServerEvent(Object... args) {
        if (args == null || args.length == 0 || !(args[0] instanceof Integer)) {
            return;
        }
        int messageId = (Integer) args[0];
        boolean dialogKnown = args.length > 3 && args[3] instanceof Long;
        long eventDialogId = dialogKnown ? (Long) args[3] : 0L;
        applyStateMutation(current -> current.observeServer(
                messageId,
                dialogKnown && matchesDialog(eventDialogId)
        ));
        if (isPreparedAndUnidentified()) {
            reconcileQueue();
        }
    }

    private void handleDeletionEvent(Object... args) {
        if (args == null || args.length < 2 || !(args[0] instanceof ArrayList) || !(args[1] instanceof Long)) {
            return;
        }

        ArrayList<?> values = (ArrayList<?>) args[0];
        ArrayList<Integer> deletedIds = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            if (value instanceof Integer) {
                deletedIds.add((Integer) value);
            }
        }
        long eventDialogId = (Long) args[1];
        boolean preparedAndMatching;
        synchronized (stateLock) {
            preparedAndMatching = state != null && matchesDialog(eventDialogId);
        }
        applyStateMutation(current -> current.observeDeletion(deletedIds, matchesDialog(eventDialogId)));
        if (preparedAndMatching && isPreparedAndUnidentified()) {
            reconcileQueue();
        }
    }
}
