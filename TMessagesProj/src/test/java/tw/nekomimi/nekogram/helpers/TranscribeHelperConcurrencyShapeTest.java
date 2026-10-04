package tw.nekomimi.nekogram.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class TranscribeHelperConcurrencyShapeTest {

    private static String source() throws Exception {
        Path[] candidates = {
                Paths.get("src/main/java/tw/nekomimi/nekogram/helpers/TranscribeHelper.java"),
                Paths.get("TMessagesProj/src/main/java/tw/nekomimi/nekogram/helpers/TranscribeHelper.java")
        };
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("TranscribeHelper.java not found");
    }

    private static int count(String source, String token) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }

    @Test
    public void helperUsesOneBoundedExecutorWithoutChangingProviderSubmitShape() throws Exception {
        String source = source();

        assertFalse(source.contains("Executors.newCached" + "ThreadPool()"));
        assertTrue(source.contains(
                "private static final ExecutorService executorService = TranscriptionExecutorFactory.create();"));
        assertEquals(1, count(source, "TranscriptionExecutorFactory.create()"));
        assertEquals(3, count(source, "executorService.submit("));

        assertTrue(source.contains("requestWorkersAi("));
        assertTrue(source.contains("requestGeminiAi("));
        assertTrue(source.contains("requestOpenAiCompatible("));
        assertTrue(source.contains("sendRequest("));

        assertFalse(source.contains("CallerRunsPolicy"));
        assertFalse(source.contains("SynchronousQueue"));
        assertFalse(source.contains("new Semaphore("));
        assertFalse(source.contains("newFixedThreadPool("));
        assertFalse(source.contains("newSingleThreadExecutor("));
    }
}
