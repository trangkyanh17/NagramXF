package com.radolyn.ayugram.utils;

import java.util.function.BooleanSupplier;

public final class AyuRevisionPresenceResolver {
    private AyuRevisionPresenceResolver() {
    }

    public static boolean isEligible(boolean featureEnabled, boolean hasFromPeer,
                                     long fromUserId, long selfUserId,
                                     boolean expiredVoiceOrRound) {
        return featureEnabled
                && hasFromPeer
                && fromUserId != selfUserId
                && !expiredVoiceOrRound;
    }

    public static boolean resolve(boolean eligible, BooleanSupplier query) {
        return eligible && query.getAsBoolean();
    }
}
