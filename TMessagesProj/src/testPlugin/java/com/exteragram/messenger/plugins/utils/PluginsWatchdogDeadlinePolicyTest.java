package com.exteragram.messenger.plugins.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class PluginsWatchdogDeadlinePolicyTest {

    private static final long TIMEOUT_MS = 5_000L;

    private static final class Execution {
        final long startedAt;

        Execution(long startedAt) {
            this.startedAt = startedAt;
        }
    }

    private static long earliest(Map<Long, Execution> active, Map<Long, Execution> frozen) {
        return PluginsWatchdogDeadlinePolicy.earliestDeadline(
                active,
                frozen,
                execution -> execution.startedAt,
                TIMEOUT_MS);
    }

    @Test
    public void noExecutionHasNoDeadline() {
        assertEquals(
                PluginsWatchdogDeadlinePolicy.NO_DEADLINE,
                earliest(new HashMap<>(), new HashMap<>()));
    }

    @Test
    public void firstExecutionProducesFirstEligibleDeadline() {
        Map<Long, Execution> active = new HashMap<>();
        active.put(1L, new Execution(1_000L));

        assertEquals(6_001L, earliest(active, new HashMap<>()));
    }

    @Test
    public void laterExecutionDoesNotReplaceEarlierDeadline() {
        Map<Long, Execution> active = new HashMap<>();
        active.put(1L, new Execution(1_000L));
        active.put(2L, new Execution(2_000L));

        assertEquals(6_001L, earliest(active, new HashMap<>()));
    }

    @Test
    public void earlierSecondExecutionMovesDeadlineEarlier() {
        Map<Long, Execution> active = new HashMap<>();
        active.put(1L, new Execution(2_000L));
        active.put(2L, new Execution(500L));

        assertEquals(5_501L, earliest(active, new HashMap<>()));
    }

    @Test
    public void finishRemovesExecutionAndRecomputesDeadline() {
        Map<Long, Execution> active = new HashMap<>();
        active.put(1L, new Execution(1_000L));
        active.put(2L, new Execution(2_000L));

        active.remove(1L);

        assertEquals(7_001L, earliest(active, new HashMap<>()));
    }

    @Test
    public void exactlyTimeoutIsNotOverdue() {
        assertFalse(PluginsWatchdogDeadlinePolicy.isOverdue(1_000L, 6_000L, TIMEOUT_MS));
    }

    @Test
    public void beyondTimeoutIsOverdue() {
        assertTrue(PluginsWatchdogDeadlinePolicy.isOverdue(1_000L, 6_001L, TIMEOUT_MS));
    }

    @Test
    public void replacementOnSameThreadInvalidatesOldIdentity() {
        Map<Long, Execution> active = new HashMap<>();
        Execution oldExecution = new Execution(1_000L);
        Execution replacement = new Execution(2_000L);
        active.put(7L, oldExecution);
        active.put(7L, replacement);

        assertFalse(PluginsWatchdogDeadlinePolicy.isCurrent(active, 7L, oldExecution));
        assertTrue(PluginsWatchdogDeadlinePolicy.isCurrent(active, 7L, replacement));
    }

    @Test
    public void alreadyFrozenIdentityIsExcludedFromDeadline() {
        Map<Long, Execution> active = new HashMap<>();
        Map<Long, Execution> frozen = new HashMap<>();
        Execution execution = new Execution(1_000L);
        active.put(7L, execution);
        frozen.put(7L, execution);

        assertEquals(PluginsWatchdogDeadlinePolicy.NO_DEADLINE, earliest(active, frozen));
    }

    @Test
    public void clearedStateHasNoDeadline() {
        Map<Long, Execution> active = new HashMap<>();
        Map<Long, Execution> frozen = new HashMap<>();
        active.put(1L, new Execution(1_000L));
        active.clear();
        frozen.clear();

        assertEquals(PluginsWatchdogDeadlinePolicy.NO_DEADLINE, earliest(active, frozen));
    }
}
