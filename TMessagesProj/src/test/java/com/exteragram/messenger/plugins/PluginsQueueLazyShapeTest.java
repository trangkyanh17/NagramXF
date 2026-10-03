package com.exteragram.messenger.plugins;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class PluginsQueueLazyShapeTest {

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
    public void pluginsQueueIsLazyAndHasOneCanonicalOwner() throws Exception {
        String utilities = source("src/main/java/org/telegram/messenger/Utilities.java");
        String controller = source("src/plugin/java/com/exteragram/messenger/plugins/PluginsController.java");

        assertTrue(utilities.contains("public static volatile DispatchQueue pluginsQueue;"));
        assertFalse(utilities.contains("pluginsQueue = new DispatchQueue(\"pluginsQueue\")"));

        String creator = method(controller, "private static DispatchQueue getOrCreatePluginsQueue()");
        assertTrue(creator.contains("Utilities.pluginsQueue"));
        assertTrue(creator.contains("isAlive()"));
        assertTrue(creator.contains("synchronized (PluginsController.class)"));
        assertTrue(creator.contains("new DispatchQueue(\"pluginsQueue\")"));
        assertTrue(creator.contains("return Utilities.pluginsQueue;"));

        String runOnQueue = method(controller, "public static void runOnPluginsQueue(Runnable runnable)");
        assertTrue(runOnQueue.contains("getOrCreatePluginsQueue().postRunnable(runnable)"));

        String loadSettings = method(controller, "public void loadPluginSettings(String pluginId)");
        assertTrue(loadSettings.contains("runOnPluginsQueue(() ->"));
        assertFalse(loadSettings.contains("Utilities.pluginsQueue.postRunnable"));

        assertFalse(controller.contains("Utilities.pluginsQueue.postRunnable("));
    }
}
