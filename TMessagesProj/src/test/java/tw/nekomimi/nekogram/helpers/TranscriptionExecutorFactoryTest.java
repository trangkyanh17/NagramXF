package tw.nekomimi.nekogram.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class TranscriptionExecutorFactoryTest {

    @Test
    public void factoryCreatesExactBoundedPolicyWithoutPrestartingWorkers() {
        ThreadPoolExecutor executor = TranscriptionExecutorFactory.create();
        try {
            assertEquals(2, executor.getCorePoolSize());
            assertEquals(2, executor.getMaximumPoolSize());
            assertEquals(60L, executor.getKeepAliveTime(TimeUnit.SECONDS));
            assertTrue(executor.allowsCoreThreadTimeOut());
            assertTrue(executor.getQueue() instanceof LinkedBlockingQueue);
            assertEquals(0, executor.getPoolSize());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void thirdJobQueuesUntilOneOfTwoActiveJobsFinishes() throws Exception {
        ThreadPoolExecutor executor = TranscriptionExecutorFactory.create();
        CountDownLatch firstTwoStarted = new CountDownLatch(2);
        CountDownLatch releaseFirstTwo = new CountDownLatch(1);
        CountDownLatch thirdStarted = new CountDownLatch(1);

        Runnable blocking = () -> {
            firstTwoStarted.countDown();
            try {
                releaseFirstTwo.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        try {
            executor.execute(blocking);
            executor.execute(blocking);
            assertTrue(firstTwoStarted.await(10, TimeUnit.SECONDS));

            executor.execute(thirdStarted::countDown);

            assertEquals(2, executor.getActiveCount());
            assertEquals(1, executor.getQueue().size());
            assertEquals(1L, thirdStarted.getCount());

            releaseFirstTwo.countDown();

            assertTrue(thirdStarted.await(10, TimeUnit.SECONDS));
        } finally {
            releaseFirstTwo.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
