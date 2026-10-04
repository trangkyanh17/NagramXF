package tw.nekomimi.nekogram.filters;

import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class AyuFilterDialogSnapshotShapeTest {

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

    @Test
    public void dialogSnapshotIntegrationHasRequiredShape() throws Exception {
        String source = source();

        assertTrue(source.contains("class ExclusionSnapshotsHolder"));
        assertTrue(source.contains("getExcludedDialogsView()"));
        assertTrue(source.contains("getRegexFiltersExcludedDialogs().String()"));
        assertTrue(source.contains(".dialogs("));
        assertTrue(source.contains("FileLog.e(\"AyuFilter.getExcludedDialogs\", e)"));
        assertTrue(source.contains(".publishDialogs("));
        assertTrue(source.contains(".invalidateAll()"));

        int setConfig = source.indexOf("getRegexFiltersExcludedDialogs().setConfigString(str)");
        int publish = source.indexOf(".publishDialogs(str");
        int clear = source.indexOf("AyuFilterCache.clearDialog(dialogId)", setConfig);
        assertTrue(setConfig >= 0);
        assertTrue(publish > setConfig);
        assertTrue(clear > publish);

        int clearAllFilters = source.indexOf("public static void clearAllFilters()");
        int rebuild = source.indexOf("rebuildCache()", clearAllFilters);
        assertTrue(clearAllFilters >= 0);
        assertTrue(rebuild > clearAllFilters);
    }
}
