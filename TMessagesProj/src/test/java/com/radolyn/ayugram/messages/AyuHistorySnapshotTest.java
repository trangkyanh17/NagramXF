package com.radolyn.ayugram.messages;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.radolyn.ayugram.database.entities.EditedMessage;
import com.radolyn.ayugram.utils.AyuAsyncRequestGate;
import com.radolyn.ayugram.utils.AyuSafeLookup;
import com.radolyn.ayugram.utils.AyuUiRequestKey;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class AyuHistorySnapshotTest {
    @Test
    public void snapshotPreservesRevisionOrder() {
        EditedMessage first = new EditedMessage();
        EditedMessage second = new EditedMessage();

        AyuHistorySnapshot snapshot = AyuHistorySnapshot.of(List.of(first, second), null);

        assertEquals(2, snapshot.getRevisions().size());
        assertSame(first, snapshot.getRevisions().get(0));
        assertSame(second, snapshot.getRevisions().get(1));
    }

    @Test
    public void snapshotDefensivelyCopiesAttachmentNames() {
        String[] source = {"one.jpg", "two.ogg"};
        AyuHistorySnapshot snapshot = AyuHistorySnapshot.of(List.of(), source);
        source[0] = "mutated.jpg";

        String[] firstRead = snapshot.getAttachmentFileNames();
        assertEquals("one.jpg", firstRead[0]);
        firstRead[1] = "mutated.ogg";
        assertEquals("two.ogg", snapshot.getAttachmentFileNames()[1]);
    }

    @Test
    public void nullAttachmentListingDoesNotDiscardRevisions() {
        EditedMessage revision = new EditedMessage();

        AyuHistorySnapshot snapshot = AyuHistorySnapshot.of(List.of(revision), null);

        assertEquals(1, snapshot.getRevisions().size());
        assertSame(revision, snapshot.getRevisions().get(0));
        assertNull(snapshot.getAttachmentFileNames());
    }

    @Test
    public void emptyNormalizesNullRevisionList() {
        AyuHistorySnapshot snapshot = AyuHistorySnapshot.of(null, null);

        assertEquals(List.of(), snapshot.getRevisions());
        assertNull(snapshot.getAttachmentFileNames());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void revisionListIsUnmodifiable() {
        ArrayList<EditedMessage> source = new ArrayList<>();
        source.add(new EditedMessage());
        AyuHistorySnapshot snapshot = AyuHistorySnapshot.of(source, null);

        snapshot.getRevisions().add(new EditedMessage());
    }

    @Test
    public void newerHistoryRequestRejectsOlderSnapshot() {
        AyuAsyncRequestGate<AyuUiRequestKey> gate = new AyuAsyncRequestGate<>();
        AyuUiRequestKey key = AyuUiRequestKey.forMessage(0, -100L, 10);
        long older = gate.begin(key);
        long newer = gate.begin(key);

        org.junit.Assert.assertFalse(gate.isCurrent(older, key));
        org.junit.Assert.assertTrue(gate.isCurrent(newer, key));
    }

    @Test
    public void destroyedHistoryRejectsPendingSnapshot() {
        AyuAsyncRequestGate<AyuUiRequestKey> gate = new AyuAsyncRequestGate<>();
        AyuUiRequestKey key = AyuUiRequestKey.forMessage(0, -100L, 10);
        long pending = gate.begin(key);

        gate.invalidate();

        org.junit.Assert.assertFalse(gate.isCurrent(pending, key));
    }

    @Test
    public void failedHistoryLookupDoesNotPoisonLaterGeneration() {
        AyuAsyncRequestGate<AyuUiRequestKey> gate = new AyuAsyncRequestGate<>();
        AyuUiRequestKey key = AyuUiRequestKey.forMessage(0, -100L, 10);
        long failedGeneration = gate.begin(key);
        AyuHistorySnapshot failed = AyuSafeLookup.run(() -> {
            throw new IllegalStateException("temporary");
        }, AyuHistorySnapshot.empty(), error -> { });

        long retryGeneration = gate.begin(key);
        EditedMessage revision = new EditedMessage();
        AyuHistorySnapshot retry = AyuSafeLookup.run(
                () -> AyuHistorySnapshot.of(List.of(revision), null),
                AyuHistorySnapshot.empty(),
                error -> { }
        );

        assertEquals(List.of(), failed.getRevisions());
        org.junit.Assert.assertFalse(gate.isCurrent(failedGeneration, key));
        org.junit.Assert.assertTrue(gate.isCurrent(retryGeneration, key));
        assertSame(revision, retry.getRevisions().get(0));
    }
}
