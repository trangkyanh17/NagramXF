package com.radolyn.ayugram.utils.seq;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

public class SendWaitSequenceTest {
    @Test
    public void selectedIdIsPassedBeforeUploadAwait() {
        List<String> events = new ArrayList<>();
        SendWaitSequence.run(42,
                id -> events.add("id:" + id),
                () -> events.add("upload"),
                null);
        assertEquals(List.of("id:42", "upload"), events);
    }

    @Test
    public void uploadAwaitCompletesBeforeMessageAwait() {
        List<String> events = new ArrayList<>();
        SendWaitSequence.run(7, null,
                () -> events.add("upload"),
                () -> events.add("message"));
        assertEquals(List.of("upload", "message"), events);
    }

    @Test
    public void noUploadStageStillAwaitsMessage() {
        AtomicInteger messageCount = new AtomicInteger();
        SendWaitSequence.run(9, null, null, messageCount::incrementAndGet);
        assertEquals(1, messageCount.get());
    }

    @Test
    public void noMessageStageDoesNotAssignUploadIdButStillAwaitsUpload() {
        AtomicInteger assignedId = new AtomicInteger(-1);
        AtomicInteger uploadCount = new AtomicInteger();
        SendWaitSequence.run(11, null, uploadCount::incrementAndGet, null);
        assertEquals(-1, assignedId.get());
        assertEquals(1, uploadCount.get());
    }
}
