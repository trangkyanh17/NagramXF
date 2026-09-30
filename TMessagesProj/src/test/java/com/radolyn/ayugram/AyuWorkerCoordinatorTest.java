package com.radolyn.ayugram;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AyuWorkerCoordinatorTest {
    private static AyuWorkerCoordinator coordinator(
            int accountCount,
            Set<Integer> active,
            Set<Integer> offlineMode,
            List<Integer> offlineSends,
            List<Integer> fetchNotifications,
            FakeScheduler scheduler) {
        return new AyuWorkerCoordinator(
                accountCount,
                active::contains,
                offlineMode::contains,
                offlineSends::add,
                fetchNotifications::add,
                scheduler);
    }

    @Test
    public void repeatedOnlineEventsRescheduleOnePendingRun() {
        FakeScheduler scheduler = new FakeScheduler();
        List<Integer> sends = new ArrayList<>();
        List<Integer> notifications = new ArrayList<>();
        AyuWorkerCoordinator coordinator = coordinator(
                2, setOf(0, 1), setOf(0, 1), sends, notifications, scheduler);

        coordinator.setOnline(0, true);
        coordinator.setOnline(0, true);

        assertEquals(2, scheduler.rescheduleCalls);
        assertNotNull(scheduler.pending);
        scheduler.fire();
        assertEquals(Arrays.asList(0), sends);
        assertEquals(Arrays.asList(0), notifications);
        scheduler.fire();
        assertEquals(1, sends.size());
    }

    @Test
    public void runOnceConsumesPendingWorkAcrossAccounts() {
        FakeScheduler scheduler = new FakeScheduler();
        List<Integer> sends = new ArrayList<>();
        List<Integer> notifications = new ArrayList<>();
        AyuWorkerCoordinator coordinator = coordinator(
                3, setOf(0, 1, 2), setOf(0, 1, 2), sends, notifications, scheduler);

        coordinator.setOnline(0, true);
        coordinator.setOnline(2, true);
        coordinator.runOnce();
        coordinator.runOnce();

        assertEquals(Arrays.asList(0, 2), sends);
        assertEquals(Arrays.asList(0, 2), notifications);
    }

    @Test
    public void clearingOneAccountDoesNotSuppressAnother() {
        FakeScheduler scheduler = new FakeScheduler();
        List<Integer> sends = new ArrayList<>();
        List<Integer> notifications = new ArrayList<>();
        AyuWorkerCoordinator coordinator = coordinator(
                2, setOf(0, 1), setOf(0, 1), sends, notifications, scheduler);

        coordinator.setOnline(0, true);
        coordinator.setOnline(1, true);
        coordinator.clearOnline(0);
        coordinator.runOnce();

        assertEquals(Arrays.asList(1), sends);
        assertEquals(Arrays.asList(1), notifications);
    }

    @Test
    public void lastSeenRefreshSendsImmediatelyWithoutQueuedDuplicate() {
        FakeScheduler scheduler = new FakeScheduler();
        List<Integer> sends = new ArrayList<>();
        List<Integer> notifications = new ArrayList<>();
        AyuWorkerCoordinator coordinator = coordinator(
                1, setOf(0), setOf(0), sends, notifications, scheduler);

        coordinator.requestLastSeenUpdate(0);

        assertEquals(Arrays.asList(0), sends);
        assertEquals(Arrays.asList(0), notifications);
        assertEquals(0, scheduler.rescheduleCalls);
        coordinator.runOnce();
        scheduler.fire();
        assertEquals(1, sends.size());
        assertEquals(1, notifications.size());
    }

    @Test
    public void lastSeenRefreshWithoutOfflineModeOnlyNotifiesFetch() {
        FakeScheduler scheduler = new FakeScheduler();
        List<Integer> sends = new ArrayList<>();
        List<Integer> notifications = new ArrayList<>();
        AyuWorkerCoordinator coordinator = coordinator(
                1, setOf(0), setOf(), sends, notifications, scheduler);

        coordinator.requestLastSeenUpdate(0);

        assertEquals(0, sends.size());
        assertEquals(Arrays.asList(0), notifications);
        assertEquals(0, scheduler.rescheduleCalls);
    }

    @Test
    public void scheduleAndShutdownDelegateToScheduler() {
        FakeScheduler scheduler = new FakeScheduler();
        AyuWorkerCoordinator coordinator = coordinator(
                1, setOf(0), setOf(0), new ArrayList<>(), new ArrayList<>(), scheduler);

        coordinator.schedule();
        assertEquals(1, scheduler.rescheduleCalls);
        assertNotNull(scheduler.pending);

        coordinator.shutdown();
        assertEquals(1, scheduler.shutdownCalls);
        assertNull(scheduler.pending);
    }

    private static Set<Integer> setOf(Integer... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    private static final class FakeScheduler implements AyuWorkerCoordinator.Scheduler {
        int rescheduleCalls;
        int shutdownCalls;
        Runnable pending;

        @Override
        public void reschedule(Runnable task) {
            rescheduleCalls++;
            pending = task;
        }

        @Override
        public void shutdown() {
            shutdownCalls++;
            pending = null;
        }

        void fire() {
            Runnable task = pending;
            pending = null;
            if (task != null) {
                task.run();
            }
        }
    }
}
