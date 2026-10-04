package tw.nekomimi.nekogram.helpers;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

final class TranscriptionExecutorFactory {

    static final int MAX_CONCURRENT_TRANSCRIPTIONS = 2;
    static final long KEEP_ALIVE_SECONDS = 60L;

    private TranscriptionExecutorFactory() {
    }

    static ThreadPoolExecutor create() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                MAX_CONCURRENT_TRANSCRIPTIONS,
                MAX_CONCURRENT_TRANSCRIPTIONS,
                KEEP_ALIVE_SECONDS,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
}
