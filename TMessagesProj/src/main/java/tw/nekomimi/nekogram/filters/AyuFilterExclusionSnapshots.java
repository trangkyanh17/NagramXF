package tw.nekomimi.nekogram.filters;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

final class AyuFilterExclusionSnapshots {

    private static final Set<String> EMPTY_SHARED = Collections.emptySet();

    private volatile DialogSnapshot dialogSnapshot;
    private volatile Map<Long, Set<String>> sharedSnapshot;

    Set<Long> dialogs(String serialized, Function<String, Set<Long>> loader) {
        DialogSnapshot snapshot = dialogSnapshot;
        if (snapshot != null && Objects.equals(snapshot.serialized, serialized)) {
            return snapshot.values;
        }

        synchronized (this) {
            snapshot = dialogSnapshot;
            if (snapshot != null && Objects.equals(snapshot.serialized, serialized)) {
                return snapshot.values;
            }

            Set<Long> loaded = loader.apply(serialized);
            Set<Long> published = immutableLongSet(loaded);
            dialogSnapshot = new DialogSnapshot(serialized, published);
            return published;
        }
    }

    synchronized void publishDialogs(String serialized, Collection<Long> values) {
        dialogSnapshot = new DialogSnapshot(serialized, immutableLongSet(values));
    }

    synchronized void invalidateDialogs() {
        dialogSnapshot = null;
    }

    Set<String> sharedView(
            long dialogId,
            Supplier<Map<Long, ? extends Set<String>>> loader) {
        Map<Long, Set<String>> snapshot = sharedSnapshot;
        if (snapshot == null) {
            synchronized (this) {
                snapshot = sharedSnapshot;
                if (snapshot == null) {
                    snapshot = immutableSharedMap(loader.get());
                    sharedSnapshot = snapshot;
                }
            }
        }
        Set<String> values = snapshot.get(dialogId);
        return values != null ? values : EMPTY_SHARED;
    }

    HashSet<String> sharedCopy(
            long dialogId,
            Supplier<Map<Long, ? extends Set<String>>> loader) {
        return new HashSet<>(sharedView(dialogId, loader));
    }

    synchronized void invalidateShared() {
        sharedSnapshot = null;
    }

    synchronized void invalidateAll() {
        dialogSnapshot = null;
        sharedSnapshot = null;
    }

    private static Set<Long> immutableLongSet(Collection<Long> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new HashSet<>(values));
    }

    private static Map<Long, Set<String>> immutableSharedMap(
            Map<Long, ? extends Set<String>> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }

        HashMap<Long, Set<String>> copy = new HashMap<>();
        for (Map.Entry<Long, ? extends Set<String>> entry : source.entrySet()) {
            Set<String> values = entry.getValue();
            Set<String> immutableValues;
            if (values == null || values.isEmpty()) {
                immutableValues = Collections.emptySet();
            } else {
                immutableValues = Collections.unmodifiableSet(new HashSet<>(values));
            }
            copy.put(entry.getKey(), immutableValues);
        }
        return Collections.unmodifiableMap(copy);
    }

    private static final class DialogSnapshot {
        final String serialized;
        final Set<Long> values;

        DialogSnapshot(String serialized, Set<Long> values) {
            this.serialized = serialized;
            this.values = values;
        }
    }
}
