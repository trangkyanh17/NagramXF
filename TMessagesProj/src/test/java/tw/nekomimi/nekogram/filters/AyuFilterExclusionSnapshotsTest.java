package tw.nekomimi.nekogram.filters;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class AyuFilterExclusionSnapshotsTest {

    @Test
    public void firstDialogLookupLoadsOnce() {
        AyuFilterExclusionSnapshots snapshots = new AyuFilterExclusionSnapshots();
        AtomicInteger loads = new AtomicInteger();

        Set<Long> result = snapshots.dialogs("[1]", raw -> {
            loads.incrementAndGet();
            return new HashSet<>(Arrays.asList(1L));
        });

        assertTrue(result.contains(1L));
        assertEquals(1, loads.get());
    }

    @Test
    public void sameRawReusesSameSnapshotWithoutReload() {
        AyuFilterExclusionSnapshots snapshots = new AyuFilterExclusionSnapshots();
        AtomicInteger loads = new AtomicInteger();

        Set<Long> first = snapshots.dialogs("[1]", raw -> {
            loads.incrementAndGet();
            return new HashSet<>(Arrays.asList(1L));
        });
        Set<Long> second = snapshots.dialogs("[1]", raw -> {
            loads.incrementAndGet();
            return new HashSet<>(Arrays.asList(2L));
        });

        assertSame(first, second);
        assertEquals(1, loads.get());
        assertTrue(second.contains(1L));
    }

    @Test
    public void changedRawReloadsBeforeAnswering() {
        AyuFilterExclusionSnapshots snapshots = new AyuFilterExclusionSnapshots();
        AtomicInteger loads = new AtomicInteger();

        snapshots.dialogs("[1]", raw -> {
            loads.incrementAndGet();
            return new HashSet<>(Arrays.asList(1L));
        });
        Set<Long> changed = snapshots.dialogs("[2]", raw -> {
            loads.incrementAndGet();
            return new HashSet<>(Arrays.asList(2L));
        });

        assertEquals(2, loads.get());
        assertFalse(changed.contains(1L));
        assertTrue(changed.contains(2L));
    }

    @Test
    public void invalidateDialogsForcesReload() {
        AyuFilterExclusionSnapshots snapshots = new AyuFilterExclusionSnapshots();
        AtomicInteger loads = new AtomicInteger();

        snapshots.dialogs("[1]", raw -> {
            loads.incrementAndGet();
            return new HashSet<>(Arrays.asList(1L));
        });
        snapshots.invalidateDialogs();
        snapshots.dialogs("[1]", raw -> {
            loads.incrementAndGet();
            return new HashSet<>(Arrays.asList(1L));
        });

        assertEquals(2, loads.get());
    }

    @Test
    public void publishDialogsCopiesCallerInput() {
        AyuFilterExclusionSnapshots snapshots = new AyuFilterExclusionSnapshots();
        HashSet<Long> source = new HashSet<>(Arrays.asList(1L));

        snapshots.publishDialogs("[1]", source);
        source.add(2L);
        Set<Long> result = snapshots.dialogs("[1]", raw -> {
            throw new AssertionError("loader must not run");
        });

        assertTrue(result.contains(1L));
        assertFalse(result.contains(2L));
    }

    @Test
    public void dialogLoaderFailureIsNotCached() {
        AyuFilterExclusionSnapshots snapshots = new AyuFilterExclusionSnapshots();
        AtomicInteger loads = new AtomicInteger();

        assertThrows(IllegalStateException.class, () -> snapshots.dialogs("bad", raw -> {
            loads.incrementAndGet();
            throw new IllegalStateException("bad input");
        }));

        Set<Long> retry = snapshots.dialogs("bad", raw -> {
            loads.incrementAndGet();
            return new HashSet<>(Arrays.asList(9L));
        });

        assertEquals(2, loads.get());
        assertTrue(retry.contains(9L));
    }

    @Test
    public void dialogSnapshotIsReadOnly() {
        AyuFilterExclusionSnapshots snapshots = new AyuFilterExclusionSnapshots();
        Set<Long> result = snapshots.dialogs("[1]", raw -> new HashSet<>(Arrays.asList(1L)));

        assertThrows(UnsupportedOperationException.class, () -> result.add(2L));
    }

    @Test
    public void nullLoadedDialogSetNormalizesToEmpty() {
        AyuFilterExclusionSnapshots snapshots = new AyuFilterExclusionSnapshots();

        Set<Long> result = snapshots.dialogs("null", raw -> null);

        assertTrue(result.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> result.add(1L));
    }

    @Test
    public void firstSharedLookupLoadsDeepSnapshotOnce() {
        AyuFilterExclusionSnapshots snapshots = new AyuFilterExclusionSnapshots();
        AtomicInteger loads = new AtomicInteger();

        Set<String> result = snapshots.sharedView(10L, () -> {
            loads.incrementAndGet();
            Map<Long, Set<String>> source = new HashMap<>();
            source.put(10L, new HashSet<>(Arrays.asList("a")));
            return source;
        });

        assertTrue(result.contains("a"));
        assertEquals(1, loads.get());
    }

    @Test
    public void sameSharedEpochReturnsSameReadOnlyView() {
        AyuFilterExclusionSnapshots snapshots = new AyuFilterExclusionSnapshots();

        Set<String> first = snapshots.sharedView(10L, () -> {
            Map<Long, Set<String>> source = new HashMap<>();
            source.put(10L, new HashSet<>(Arrays.asList("a")));
            return source;
        });
        Set<String> second = snapshots.sharedView(10L, () -> {
            throw new AssertionError("loader must not run");
        });

        assertSame(first, second);
        assertThrows(UnsupportedOperationException.class, () -> second.add("b"));
    }

    @Test
    public void sourceMapMutationAfterLoadCannotChangeSnapshot() {
        AyuFilterExclusionSnapshots snapshots = new AyuFilterExclusionSnapshots();
        HashSet<String> sourceSet = new HashSet<>(Arrays.asList("a"));
        Map<Long, Set<String>> source = new HashMap<>();
        source.put(10L, sourceSet);

        Set<String> view = snapshots.sharedView(10L, () -> source);
        sourceSet.add("b");
        source.put(11L, new HashSet<>(Arrays.asList("c")));

        assertTrue(view.contains("a"));
        assertFalse(view.contains("b"));
        assertTrue(snapshots.sharedView(11L, () -> {
            throw new AssertionError("loader must not run");
        }).isEmpty());
    }

    @Test
    public void sharedViewRejectsMutation() {
        AyuFilterExclusionSnapshots snapshots = new AyuFilterExclusionSnapshots();

        Set<String> view = snapshots.sharedView(10L, () -> {
            Map<Long, Set<String>> source = new HashMap<>();
            source.put(10L, new HashSet<>(Arrays.asList("a")));
            return source;
        });

        assertThrows(UnsupportedOperationException.class, () -> view.remove("a"));
    }

    @Test
    public void sharedCopyIsMutableAndIndependent() {
        AyuFilterExclusionSnapshots snapshots = new AyuFilterExclusionSnapshots();
        HashSet<String> copy = snapshots.sharedCopy(10L, () -> {
            Map<Long, Set<String>> source = new HashMap<>();
            source.put(10L, new HashSet<>(Arrays.asList("a")));
            return source;
        });

        copy.add("b");

        Set<String> view = snapshots.sharedView(10L, () -> {
            throw new AssertionError("loader must not run");
        });
        assertTrue(copy.contains("b"));
        assertFalse(view.contains("b"));
    }

    @Test
    public void invalidateSharedForcesReload() {
        AyuFilterExclusionSnapshots snapshots = new AyuFilterExclusionSnapshots();
        AtomicInteger loads = new AtomicInteger();

        snapshots.sharedView(10L, () -> {
            loads.incrementAndGet();
            return Map.of(10L, Set.of("a"));
        });
        snapshots.invalidateShared();
        snapshots.sharedView(10L, () -> {
            loads.incrementAndGet();
            return Map.of(10L, Set.of("b"));
        });

        assertEquals(2, loads.get());
    }

    @Test
    public void missingDialogReturnsReadOnlyEmptyViewAndMutableCopy() {
        AyuFilterExclusionSnapshots snapshots = new AyuFilterExclusionSnapshots();

        Set<String> view = snapshots.sharedView(99L, HashMap::new);
        HashSet<String> copy = snapshots.sharedCopy(99L, () -> {
            throw new AssertionError("loader must not run");
        });

        assertTrue(view.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> view.add("x"));
        copy.add("x");
        assertTrue(copy.contains("x"));
        assertTrue(view.isEmpty());
    }

    @Test
    public void invalidateRacingFirstLoadWinsForNextLookup() throws Exception {
        AyuFilterExclusionSnapshots snapshots = new AyuFilterExclusionSnapshots();
        CountDownLatch loaderStarted = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        CountDownLatch firstDone = new CountDownLatch(1);
        CountDownLatch invalidationDone = new CountDownLatch(1);
        AtomicInteger loads = new AtomicInteger();

        Thread loader = new Thread(() -> {
            try {
                snapshots.sharedView(10L, () -> {
                    loads.incrementAndGet();
                    loaderStarted.countDown();
                    try {
                        if (!releaseLoader.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("loader release timed out");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                    return Map.of(10L, Set.of("old"));
                });
            } finally {
                firstDone.countDown();
            }
        });
        loader.start();

        assertTrue(loaderStarted.await(5, TimeUnit.SECONDS));

        Thread invalidator = new Thread(() -> {
            snapshots.invalidateShared();
            invalidationDone.countDown();
        });
        invalidator.start();

        releaseLoader.countDown();
        assertTrue(firstDone.await(5, TimeUnit.SECONDS));
        assertTrue(invalidationDone.await(5, TimeUnit.SECONDS));

        Set<String> fresh = snapshots.sharedView(10L, () -> {
            loads.incrementAndGet();
            return Map.of(10L, Set.of("fresh"));
        });

        assertEquals(2, loads.get());
        assertTrue(fresh.contains("fresh"));
        assertFalse(fresh.contains("old"));
    }
}
