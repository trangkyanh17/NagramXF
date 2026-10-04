package com.radolyn.ayugram.controllers;

import static org.junit.Assert.assertEquals;

import com.radolyn.ayugram.database.entities.SpyMessageContentsRead;
import com.radolyn.ayugram.database.entities.SpyMessageRead;
import com.radolyn.ayugram.utils.AyuSafeLookup;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

public class AyuReadDateResolverTest {
    @Test
    public void messageReadWinsWithoutContentsQuery() {
        SpyMessageRead read = new SpyMessageRead();
        read.entityCreateDate = 111;
        AtomicInteger contentsQueries = new AtomicInteger();

        int result = AyuReadDateResolver.resolve(true, () -> read, () -> {
            contentsQueries.incrementAndGet();
            return new SpyMessageContentsRead();
        });

        assertEquals(111, result);
        assertEquals(0, contentsQueries.get());
    }

    @Test
    public void contentsReadIsFallback() {
        SpyMessageContentsRead contents = new SpyMessageContentsRead();
        contents.entityCreateDate = 222;

        int result = AyuReadDateResolver.resolve(true, () -> null, () -> contents);

        assertEquals(222, result);
    }

    @Test
    public void noRowsReturnsZero() {
        assertEquals(0, AyuReadDateResolver.resolve(true, () -> null, () -> null));
    }

    @Test
    public void disabledDoesNotQuery() {
        AtomicInteger queries = new AtomicInteger();

        int result = AyuReadDateResolver.resolve(false, () -> {
            queries.incrementAndGet();
            return new SpyMessageRead();
        }, () -> {
            queries.incrementAndGet();
            return new SpyMessageContentsRead();
        });

        assertEquals(0, result);
        assertEquals(0, queries.get());
    }

    @Test
    public void lookupFailureReturnsZeroAndNextCallCanRetry() {
        AtomicInteger errors = new AtomicInteger();
        int failed = AyuSafeLookup.run(
                () -> AyuReadDateResolver.resolve(true, () -> {
                    throw new IllegalStateException("temporary");
                }, () -> null),
                0,
                error -> errors.incrementAndGet()
        );

        SpyMessageRead read = new SpyMessageRead();
        read.entityCreateDate = 333;
        int retried = AyuSafeLookup.run(
                () -> AyuReadDateResolver.resolve(true, () -> read, () -> null),
                0,
                error -> errors.incrementAndGet()
        );

        assertEquals(0, failed);
        assertEquals(333, retried);
        assertEquals(1, errors.get());
    }
}
