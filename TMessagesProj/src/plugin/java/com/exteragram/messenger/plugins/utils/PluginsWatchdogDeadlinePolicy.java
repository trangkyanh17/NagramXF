package com.exteragram.messenger.plugins.utils;

import java.util.Map;
import java.util.function.ToLongFunction;

final class PluginsWatchdogDeadlinePolicy {

    static final long NO_DEADLINE = Long.MAX_VALUE;

    private PluginsWatchdogDeadlinePolicy() {
    }

    static long firstEligibleDeadline(long startedAt, long timeoutMs) {
        long increment;
        try {
            increment = Math.addExact(timeoutMs, 1L);
            return Math.addExact(startedAt, increment);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    static boolean isOverdue(long startedAt, long now, long timeoutMs) {
        return now >= firstEligibleDeadline(startedAt, timeoutMs);
    }

    static <T> boolean isCurrent(Map<Long, T> active, long threadId, T identity) {
        return active.get(threadId) == identity;
    }

    static <T> long earliestDeadline(
            Map<Long, T> active,
            Map<Long, T> frozen,
            ToLongFunction<T> startedAt,
            long timeoutMs) {
        long earliest = NO_DEADLINE;
        for (Map.Entry<Long, T> entry : active.entrySet()) {
            T identity = entry.getValue();
            if (frozen.get(entry.getKey()) == identity) {
                continue;
            }
            long deadline = firstEligibleDeadline(startedAt.applyAsLong(identity), timeoutMs);
            if (deadline < earliest) {
                earliest = deadline;
            }
        }
        return earliest;
    }
}
