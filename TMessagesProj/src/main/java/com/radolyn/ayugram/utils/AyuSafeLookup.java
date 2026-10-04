package com.radolyn.ayugram.utils;

import java.util.function.Consumer;
import java.util.function.Supplier;

public final class AyuSafeLookup {
    private AyuSafeLookup() {
    }

    public static <T> T run(Supplier<T> supplier, T fallback, Consumer<Throwable> errorConsumer) {
        try {
            return supplier.get();
        } catch (RuntimeException error) {
            errorConsumer.accept(error);
            return fallback;
        }
    }
}
