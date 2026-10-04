package com.radolyn.ayugram.utils.seq;

final class DispatchCompletionRunner {
    private DispatchCompletionRunner() {
    }

    static void run(Runnable dispatch, Runnable completion) {
        try {
            dispatch.run();
        } finally {
            completion.run();
        }
    }
}
