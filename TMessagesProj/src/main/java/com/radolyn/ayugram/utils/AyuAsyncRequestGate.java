package com.radolyn.ayugram.utils;

import java.util.Objects;

public final class AyuAsyncRequestGate<K> {
    private long generation;
    private K currentKey;

    public long begin(K key) {
        currentKey = key;
        return ++generation;
    }

    public boolean isCurrent(long requestGeneration, K key) {
        return requestGeneration == generation && Objects.equals(currentKey, key);
    }

    public void invalidate() {
        currentKey = null;
        generation++;
    }
}
