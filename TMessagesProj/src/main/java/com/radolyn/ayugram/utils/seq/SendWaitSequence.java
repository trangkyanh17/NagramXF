package com.radolyn.ayugram.utils.seq;

import java.util.function.IntConsumer;

final class SendWaitSequence {
    private SendWaitSequence() {
    }

    static void run(int sendingId, IntConsumer uploadIdSink, Runnable uploadAwait, Runnable messageAwait) {
        if (uploadIdSink != null) {
            uploadIdSink.accept(sendingId);
        }
        if (uploadAwait != null) {
            uploadAwait.run();
        }
        if (messageAwait != null) {
            messageAwait.run();
        }
    }
}
