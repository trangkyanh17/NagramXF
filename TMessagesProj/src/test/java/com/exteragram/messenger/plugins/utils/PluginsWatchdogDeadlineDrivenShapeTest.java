package com.exteragram.messenger.plugins.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class PluginsWatchdogDeadlineDrivenShapeTest {

    private static String source() throws Exception {
        Path[] candidates = {
                Paths.get("src/plugin/java/com/exteragram/messenger/plugins/utils/PluginsWatchdog.java"),
                Paths.get("TMessagesProj/src/plugin/java/com/exteragram/messenger/plugins/utils/PluginsWatchdog.java")
        };
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("PluginsWatchdog.java not found");
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
    public void watchdogUsesOneShotDeadlinesInsteadOfPolling() throws Exception {
        String source = source();

        assertFalse(source.contains("scheduleWithFixedDelay"));
        assertFalse(source.contains("scheduleAtFixedRate"));
        assertTrue(source.contains("ScheduledThreadPoolExecutor scheduler"));
        assertTrue(source.contains("ScheduledFuture<?> scheduledCheck"));
        assertTrue(source.contains("private final Object schedulerLock"));
        assertTrue(source.contains("private boolean running"));

        String start = method(source, "public void start()");
        assertTrue(start.contains("running = true"));
        assertTrue(start.contains("scheduleNextCheck()"));
        assertFalse(start.contains(".schedule("));

        String executionStart = method(source, "public void onPluginExecutionStarted(String pluginId)");
        String executionFinish = method(source, "public void onPluginExecutionFinished(String pluginId)");
        assertTrue(executionStart.contains("scheduleNextCheck()"));
        assertTrue(executionFinish.contains("scheduleNextCheck()"));

        String schedulerFactory = method(source, "private ScheduledThreadPoolExecutor getOrCreateSchedulerLocked()");
        assertTrue(schedulerFactory.contains("new ScheduledThreadPoolExecutor(1)"));
        assertTrue(schedulerFactory.contains("setRemoveOnCancelPolicy(true)"));
        assertTrue(schedulerFactory.contains("setKeepAliveTime("));
        assertTrue(schedulerFactory.contains("allowCoreThreadTimeOut(true)"));

        String schedule = method(source, "private void scheduleNextCheck()");
        assertTrue(schedule.contains("PluginsWatchdogDeadlinePolicy.earliestDeadline("));
        assertTrue(schedule.contains("scheduledCheck.cancel(false)"));
        assertTrue(schedule.contains(".schedule("));

        String callback = method(source, "private void runWatchdogCheck()");
        assertTrue(callback.contains("PluginsWatchdogDeadlinePolicy.isOverdue("));
        assertTrue(callback.contains("freezeExecutionIfRunning("));
        assertTrue(callback.contains("NotificationCenter.pluginIsNotResponding"));
        assertTrue(callback.contains("scheduleNextCheck()"));

        String stop = method(source, "public void stop()");
        assertTrue(stop.contains("running = false"));
        assertTrue(stop.contains("scheduledCheck.cancel(false)"));
        assertTrue(stop.contains("shutdownNow()"));
        assertTrue(stop.contains("frozenExecutions.clear()"));
        assertTrue(stop.contains("executingPlugins.clear()"));
    }
}
