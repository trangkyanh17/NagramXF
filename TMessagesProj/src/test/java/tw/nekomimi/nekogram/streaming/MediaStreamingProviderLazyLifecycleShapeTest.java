package tw.nekomimi.nekogram.streaming;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class MediaStreamingProviderLazyLifecycleShapeTest {

    private static String source() throws Exception {
        Path[] candidates = {
                Paths.get("src/main/java/tw/nekomimi/nekogram/streaming/MediaStreamingProvider.java"),
                Paths.get("TMessagesProj/src/main/java/tw/nekomimi/nekogram/streaming/MediaStreamingProvider.java")
        };
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("MediaStreamingProvider.java not found");
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) {
            throw new AssertionError("missing method: " + signature);
        }
        int brace = source.indexOf('{', start);
        int depth = 0;
        boolean inString = false;
        boolean inChar = false;
        boolean escaped = false;
        for (int i = brace; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (inString || inChar) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (inString && ch == '"') {
                    inString = false;
                } else if (inChar && ch == '\'') {
                    inChar = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
            } else if (ch == '\'') {
                inChar = true;
            } else if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(start, i + 1);
                }
            }
        }
        throw new AssertionError("unterminated method: " + signature);
    }

    @Test
    public void providerWorkerIsLazyAndProviderOwned() throws Exception {
        String source = source();
        String onCreate = method(source, "public boolean onCreate()");
        String openFile = method(source, "public ParcelFileDescriptor openFile(");
        String shutdown = method(source, "public void shutdown()");
        String helper = method(source, "private synchronized Handler getCallbackHandler()");

        assertTrue(onCreate.contains("return true;"));
        assertFalse(onCreate.contains("new HandlerThread("));
        assertFalse(onCreate.contains(".start()"));
        assertFalse(onCreate.contains("new Handler("));

        assertTrue(helper.contains("callbackHandler != null"));
        assertTrue(helper.contains("new HandlerThread(\"MediaStreamingProvider\")"));
        assertTrue(helper.indexOf(".start()") < helper.indexOf(".getLooper()"));
        assertTrue(helper.contains("callbackThread ="));
        assertTrue(helper.contains("callbackHandler ="));
        assertTrue(helper.contains("return callbackHandler;"));

        int contextCheck = openFile.indexOf("if (context == null)");
        int modeCheck = openFile.indexOf("if (!\"r\".equals(mode))");
        int lazyAcquire = openFile.indexOf("getCallbackHandler()");
        int proxyOpen = openFile.indexOf("return storageManager.openProxyFileDescriptor");

        assertTrue(contextCheck >= 0);
        assertTrue(modeCheck > contextCheck);
        assertTrue(proxyOpen > modeCheck);
        assertTrue(lazyAcquire > proxyOpen);
        assertTrue(openFile.contains("openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback, getCallbackHandler())"));

        assertTrue(shutdown.contains("if (callbackThread != null)"));
        assertTrue(shutdown.contains("callbackThread.quit()"));
        assertTrue(shutdown.contains("callbackHandler = null"));
        assertTrue(shutdown.contains("callbackThread = null"));

        int helperStart = source.indexOf("private synchronized Handler getCallbackHandler()");
        int helperEnd = helperStart + helper.length();
        int threadCtor = source.indexOf("new HandlerThread(\"MediaStreamingProvider\")");
        assertTrue(threadCtor >= helperStart && threadCtor < helperEnd);
        assertFalse(openFile.contains("new HandlerThread("));
    }
}
