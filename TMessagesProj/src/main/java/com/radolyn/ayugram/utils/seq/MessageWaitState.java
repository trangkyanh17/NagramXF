package com.radolyn.ayugram.utils.seq;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class MessageWaitState {
    private final Set<Integer> baselineIds = new HashSet<>();
    private final Set<Integer> terminalIds = new HashSet<>();

    private int baselinePendingCount;
    private int sendingId;
    private boolean failed;
    private boolean complete;
    private boolean lookupExpired;

    MessageWaitState(Collection<Integer> baselineIds) {
        if (baselineIds == null) {
            return;
        }
        for (Integer id : baselineIds) {
            if (id != null) {
                this.baselineIds.add(id);
            }
        }
        baselinePendingCount = this.baselineIds.size();
    }

    synchronized void observeSnapshot(List<Integer> currentIds) {
        if (currentIds == null) {
            return;
        }
        if (sendingId == 0) {
            for (Integer id : currentIds) {
                if (id == null || baselineIds.contains(id)) {
                    continue;
                }
                sendingId = id;
                if (terminalIds.contains(id)) {
                    complete = true;
                }
                break;
            }
        }
        evaluateQueueCount(currentIds.size());
    }

    synchronized void expireLookupWindow(List<Integer> finalSnapshot) {
        if (finalSnapshot != null) {
            observeSnapshot(finalSnapshot);
        }
        lookupExpired = true;
        if (finalSnapshot != null) {
            evaluateQueueCount(finalSnapshot.size());
        }
    }

    private void evaluateQueueCount(int currentCount) {
        if (complete || currentCount > baselinePendingCount) {
            return;
        }
        if (sendingId != 0 || lookupExpired && !terminalIds.isEmpty()) {
            complete = true;
        }
    }

    synchronized void observeTerminal(int messageId, boolean failure) {
        if (failure) {
            failed = true;
        }
        terminalIds.add(messageId);
        if (sendingId != 0 && sendingId == messageId) {
            complete = true;
        }
    }

    synchronized void observeServer(int messageId, boolean matchingDialog) {
        terminalIds.add(messageId);
        if (sendingId != 0) {
            if (sendingId == messageId) {
                complete = true;
            }
            return;
        }
        if (matchingDialog && !baselineIds.contains(messageId)) {
            sendingId = messageId;
            complete = true;
        }
    }

    synchronized void observeDeletion(List<Integer> deletedIds, boolean matchingDialog) {
        if (!matchingDialog || deletedIds == null) {
            return;
        }
        for (Integer id : deletedIds) {
            if (id == null) {
                continue;
            }
            if (sendingId == 0) {
                terminalIds.add(id);
            } else if (sendingId == id) {
                complete = true;
                return;
            }
        }
    }

    synchronized int getSendingId() {
        return sendingId;
    }

    synchronized boolean hasFailed() {
        return failed;
    }

    synchronized boolean isComplete() {
        return complete;
    }
}
