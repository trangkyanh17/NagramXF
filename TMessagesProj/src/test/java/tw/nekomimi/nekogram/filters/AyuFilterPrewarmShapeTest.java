package tw.nekomimi.nekogram.filters;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class AyuFilterPrewarmShapeTest {

    private static String source(String relative) throws Exception {
        Path[] candidates = {
                Paths.get(relative),
                Paths.get("TMessagesProj").resolve(relative)
        };
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException(relative + " not found");
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

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) {
            throw new AssertionError("missing method: " + signature);
        }
        int brace = source.indexOf('{', start);
        int depth = 0;
        for (int i = brace; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (ch == '{') {
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
    public void ayuFilterPrewarmIntegrationHasRequiredShape() throws Exception {
        String source = source("src/main/java/tw/nekomimi/nekogram/filters/AyuFilter.java");

        assertTrue(source.contains("AyuFilterPrewarmCoordinator"));
        assertTrue(source.contains("public static void schedulePrewarmIfEnabled()"));
        assertTrue(source.contains("NaConfig.INSTANCE.getRegexFiltersEnabled().Bool()"));
        assertTrue(source.contains("Utilities.globalQueue.postRunnable"));
        assertTrue(source.contains("getRegexFilters();"));
        assertTrue(source.contains("getChatFilterEntries();"));
        assertTrue(source.contains("getExcludedSharedFilterIdsView(Long.MIN_VALUE)"));
        assertTrue(count(source, "isCurrent(token)") >= 4);

        String rebuild = method(source, "public static void rebuildCache()");
        assertTrue(rebuild.contains("PREWARM_COORDINATOR.invalidate()"));
        assertTrue(rebuild.contains("schedulePrewarmIfEnabled();"));
        assertTrue(rebuild.indexOf("PREWARM_COORDINATOR.invalidate()")
                < rebuild.indexOf("schedulePrewarmIfEnabled();"));

        assertTrue(count(source, "PREWARM_COORDINATOR.invalidate()") >= 4);
        assertTrue(count(source, "schedulePrewarmIfEnabled();") >= 4);

        String invalidateFiltered = method(source, "public static void invalidateFilteredCache()");
        assertFalse(invalidateFiltered.contains("PREWARM_COORDINATOR.invalidate()"));
        assertFalse(invalidateFiltered.contains("schedulePrewarmIfEnabled()"));

        String getRegex = method(source, "public static ArrayList<FilterModel> getRegexFilters()");
        String getChat = method(source, "public static ArrayList<ChatFilterEntry> getChatFilterEntries()");
        assertTrue(getRegex.contains("loadSharedFilters()"));
        assertTrue(getChat.contains("loadChatFilterEntries()"));

        assertFalse(source.contains("new Thread("));
        assertFalse(source.contains("new DispatchQueue("));
        assertFalse(source.contains("new HandlerThread("));
        assertFalse(source.contains("Executors."));
        assertFalse(source.contains("new Timer("));
        assertFalse(source.contains("ScheduledExecutor"));
    }
}
