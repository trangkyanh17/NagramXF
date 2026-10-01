package com.radolyn.ayugram.controllers;

import com.radolyn.ayugram.database.entities.SpyMessageContentsRead;
import com.radolyn.ayugram.database.entities.SpyMessageRead;

import java.util.function.Supplier;

public final class AyuReadDateResolver {
    private AyuReadDateResolver() {
    }

    public static int resolve(boolean enabled,
                              Supplier<SpyMessageRead> readLookup,
                              Supplier<SpyMessageContentsRead> contentsLookup) {
        if (!enabled) {
            return 0;
        }
        SpyMessageRead read = readLookup.get();
        if (read != null) {
            return read.entityCreateDate;
        }
        SpyMessageContentsRead contentsRead = contentsLookup.get();
        return contentsRead != null ? contentsRead.entityCreateDate : 0;
    }
}
