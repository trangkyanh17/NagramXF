package tw.nekomimi.nekogram.filters;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class AyuFilterSharedSnapshotShapeTest {

    private static String source() throws Exception {
        Path[] candidates = {
                Paths.get("src/main/java/tw/nekomimi/nekogram/filters/AyuFilter.java"),
                Paths.get("TMessagesProj/src/main/java/tw/nekomimi/nekogram/filters/AyuFilter.java")
        };
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("AyuFilter.java not found");
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
    public void sharedSnapshotIntegrationHasRequiredShape() throws Exception {
        String source = source();

        assertFalse(source.contains("volatile HashMap<Long, HashSet<String>> excludedSharedFilterIdsByDialog"));
        assertFalse(source.contains("excludedSharedFilterIdsByDialog = null"));
        assertTrue(source.contains("loadExcludedSharedFilterIdsMap()"));
        assertTrue(source.contains("getExcludedSharedFilterIdsView(long dialogId)"));
        assertTrue(count(source, "getExcludedSharedFilterIdsView(dialogId)") >= 3);
        assertTrue(source.contains("public static HashSet<String> getExcludedSharedFilterIds(long dialogId)"));
        assertTrue(source.contains(".sharedCopy(dialogId"));
        assertTrue(count(source, ".invalidateShared()") >= 3);
        assertTrue(source.contains(".invalidateAll()"));
    }
}
