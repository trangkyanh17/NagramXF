package com.radolyn.ayugram.utils;

public final class AyuUiRequestKey {
    public final int account;
    public final long dialogId;
    public final int messageId;
    public final Object targetIdentity;

    private AyuUiRequestKey(int account, long dialogId, int messageId, Object targetIdentity) {
        this.account = account;
        this.dialogId = dialogId;
        this.messageId = messageId;
        this.targetIdentity = targetIdentity;
    }

    public static AyuUiRequestKey forMessage(int account, long dialogId, int messageId) {
        return new AyuUiRequestKey(account, dialogId, messageId, null);
    }

    public static AyuUiRequestKey forTarget(int account, long dialogId, int messageId, Object targetIdentity) {
        return new AyuUiRequestKey(account, dialogId, messageId, targetIdentity);
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof AyuUiRequestKey key)) {
            return false;
        }
        return account == key.account
                && dialogId == key.dialogId
                && messageId == key.messageId
                && targetIdentity == key.targetIdentity;
    }

    @Override
    public int hashCode() {
        int result = Integer.hashCode(account);
        result = 31 * result + Long.hashCode(dialogId);
        result = 31 * result + Integer.hashCode(messageId);
        result = 31 * result + System.identityHashCode(targetIdentity);
        return result;
    }
}
