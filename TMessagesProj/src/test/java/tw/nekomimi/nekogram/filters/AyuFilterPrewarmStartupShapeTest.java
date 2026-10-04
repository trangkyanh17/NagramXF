package tw.nekomimi.nekogram.filters;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class AyuFilterPrewarmStartupShapeTest {

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

    @Test
    public void startupSchedulesAfterNaConfigWithoutDirectRoomLoading() throws Exception {
        String source = source("src/main/java/org/telegram/messenger/ApplicationLoader.java");

        int configInit = source.indexOf("NaConfig.init();");
        int prewarm = source.indexOf("AyuFilter.schedulePrewarmIfEnabled();");
        int accountSetup = source.indexOf("for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++)", prewarm);

        assertTrue(configInit >= 0);
        assertTrue(prewarm > configInit);
        assertTrue(accountSetup > prewarm);
        assertFalse(source.contains("AyuFilter.getRegexFilters()"));
        assertFalse(source.contains("AyuFilter.getChatFilterEntries()"));
        assertFalse(source.contains("AyuData.getRegexFilterDao()"));
    }

    @Test
    public void settingsSchedulesOnlyOnEnableTransitions() throws Exception {
        String source = source("src/main/java/tw/nekomimi/nekogram/filters/RegexFiltersSettingActivity.java");

        assertTrue(source.contains(
                "key.equals(NaConfig.INSTANCE.getRegexFiltersEnabled().getKey()) && (boolean) newValue"));
        assertTrue(count(source, "AyuFilter.schedulePrewarmIfEnabled();") >= 2);
        assertTrue(source.contains("NaConfig.INSTANCE.getRegexFiltersEnabled().setConfigBool(true);"));
        assertTrue(source.contains("AyuFilter.invalidateFilteredCache();"));

        assertFalse(source.contains(
                "key.equals(NaConfig.INSTANCE.getRegexFiltersEnabled().getKey()) && !(boolean) newValue"));
    }
}
