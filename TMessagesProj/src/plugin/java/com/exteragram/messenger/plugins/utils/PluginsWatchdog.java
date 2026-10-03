package com.exteragram.messenger.plugins.utils;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;

import com.exteragram.messenger.plugins.Plugin;
import com.exteragram.messenger.plugins.PluginsController;
import com.exteragram.messenger.plugins.pip.PipController;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class PluginsWatchdog {
    private static final long FREEZE_TIMEOUT_MS = 5_000L;

    private final PluginsController controller;
    private final ConcurrentHashMap<Long, ExecutionInfo> executingPlugins = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ExecutionInfo> frozenExecutions = new ConcurrentHashMap<>();
    private final Object schedulerLock = new Object();
    private boolean running;
    private ScheduledThreadPoolExecutor scheduler;
    private ScheduledFuture<?> scheduledCheck;
    private long scheduledDeadlineMs = PluginsWatchdogDeadlinePolicy.NO_DEADLINE;

    private static final class ExecutionInfo {
        final String pluginId;
        final long startTime;

        private ExecutionInfo(String pluginId, long startTime) {
            this.pluginId = pluginId;
            this.startTime = startTime;
        }
    }

    public PluginsWatchdog(PluginsController controller) {
        this.controller = controller;
    }

    public void start() {
        synchronized (schedulerLock) {
            if (running) {
                return;
            }
            running = true;
        }
        scheduleNextCheck();
    }

    public void stop() {
        ScheduledThreadPoolExecutor schedulerToShutdown;
        synchronized (schedulerLock) {
            running = false;
            if (scheduledCheck != null) {
                scheduledCheck.cancel(false);
                scheduledCheck = null;
            }
            scheduledDeadlineMs = PluginsWatchdogDeadlinePolicy.NO_DEADLINE;
            schedulerToShutdown = scheduler;
            scheduler = null;
        }
        if (schedulerToShutdown != null) {
            schedulerToShutdown.shutdownNow();
        }
        for (ExecutionInfo info : frozenExecutions.values()) {
            Plugin plugin = controller.plugins.get(info.pluginId);
            if (plugin != null) {
                plugin.setNotResponding(false);
            }
        }
        frozenExecutions.clear();
        executingPlugins.clear();
    }

    public void onPluginExecutionStarted(String pluginId) {
        if (pluginId == null) {
            return;
        }
        long threadId = Thread.currentThread().getId();
        ExecutionInfo info = new ExecutionInfo(pluginId, System.currentTimeMillis());
        executingPlugins.put(threadId, info);
        ExecutionInfo frozen = frozenExecutions.remove(threadId);
        if (frozen != null) {
            if (Objects.equals(frozen.pluginId, pluginId)) {
                frozenExecutions.put(threadId, info);
            } else {
                clearNotResponding(frozen);
            }
        }
        scheduleNextCheck();
    }

    public void onPluginExecutionFinished(String pluginId) {
        long threadId = Thread.currentThread().getId();
        ExecutionInfo info = executingPlugins.get(threadId);
        if (info == null || !Objects.equals(info.pluginId, pluginId) || !executingPlugins.remove(threadId, info)) {
            return;
        }
        ExecutionInfo frozen = frozenExecutions.remove(threadId);
        if (frozen != null) {
            clearNotResponding(frozen);
        }
        scheduleNextCheck();
    }

    private ScheduledThreadPoolExecutor getOrCreateSchedulerLocked() {
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = new ScheduledThreadPoolExecutor(1);
            scheduler.setRemoveOnCancelPolicy(true);
            scheduler.setKeepAliveTime(1L, TimeUnit.SECONDS);
            scheduler.allowCoreThreadTimeOut(true);
        }
        return scheduler;
    }

    private void scheduleNextCheck() {
        synchronized (schedulerLock) {
            if (!running) {
                return;
            }
            long deadline = PluginsWatchdogDeadlinePolicy.earliestDeadline(
                    executingPlugins,
                    frozenExecutions,
                    info -> info.startTime,
                    FREEZE_TIMEOUT_MS);
            if (deadline == PluginsWatchdogDeadlinePolicy.NO_DEADLINE) {
                if (scheduledCheck != null) {
                    scheduledCheck.cancel(false);
                    scheduledCheck = null;
                }
                scheduledDeadlineMs = PluginsWatchdogDeadlinePolicy.NO_DEADLINE;
                return;
            }
            if (scheduledCheck != null
                    && !scheduledCheck.isDone()
                    && scheduledDeadlineMs == deadline) {
                return;
            }
            if (scheduledCheck != null) {
                scheduledCheck.cancel(false);
            }
            long delay = Math.max(0L, deadline - System.currentTimeMillis());
            scheduledDeadlineMs = deadline;
            scheduledCheck = getOrCreateSchedulerLocked().schedule(
                    this::runWatchdogCheck,
                    delay,
                    TimeUnit.MILLISECONDS);
        }
    }

    private void runWatchdogCheck() {
        synchronized (schedulerLock) {
            if (!running) {
                return;
            }
            scheduledCheck = null;
            scheduledDeadlineMs = PluginsWatchdogDeadlinePolicy.NO_DEADLINE;
        }
        try {
            long now = System.currentTimeMillis();
            boolean notify = false;
            for (Map.Entry<Long, ExecutionInfo> entry : executingPlugins.entrySet()) {
                ExecutionInfo info = entry.getValue();
                if (!PluginsWatchdogDeadlinePolicy.isOverdue(info.startTime, now, FREEZE_TIMEOUT_MS)) {
                    continue;
                }
                if (freezeExecutionIfRunning(entry.getKey(), info)) {
                    notify = true;
                }
            }
            if (notify && isRunning()) {
                NotificationCenter.getGlobalInstance().postNotificationNameOnUIThread(NotificationCenter.pluginIsNotResponding);
            }
        } catch (Throwable t) {
            FileLog.e(t);
        } finally {
            scheduleNextCheck();
        }
    }

    private boolean isRunning() {
        synchronized (schedulerLock) {
            return running;
        }
    }

    private boolean freezeExecutionIfRunning(long threadId, ExecutionInfo info) {
        synchronized (schedulerLock) {
            if (!running) {
                return false;
            }
            return freezeExecution(threadId, info);
        }
    }

    private boolean freezeExecution(long threadId, ExecutionInfo info) {
        final boolean[] flagged = new boolean[1];
        executingPlugins.computeIfPresent(threadId, (key, current) -> {
            if (current == info) {
                frozenExecutions.put(key, current);
                Plugin plugin = controller.plugins.get(current.pluginId);
                if (plugin != null && !plugin.isNotResponding()) {
                    plugin.setNotResponding(true);
                    flagged[0] = true;
                }
            }
            return current;
        });
        return flagged[0];
    }

    private void clearNotResponding(ExecutionInfo frozen) {
        for (ExecutionInfo other : frozenExecutions.values()) {
            if (Objects.equals(other.pluginId, frozen.pluginId)) {
                return;
            }
        }
        Plugin plugin = controller.plugins.get(frozen.pluginId);
        if (plugin != null) {
            plugin.setNotResponding(false);
        }
        NotificationCenter.getGlobalInstance().postNotificationNameOnUIThread(NotificationCenter.pluginIsNotResponding);
    }

    public void forceDisablePlugin(String pluginId, Activity activity) {
        disablePluginPref(pluginId);
        restartApp(activity);
    }

    public void forceDeletePlugin(String pluginId, Activity activity) {
        disablePluginPref(pluginId);
        PluginsController.setPluginPinned(pluginId, false);
        controller.removeHooksByPluginId(pluginId);
        controller.invalidatePluginSettings(pluginId);
        controller.removeMenuItemsByPluginId(pluginId);
        controller.clearPluginSettingsPreferences(pluginId);
        try {
            PipController.INSTANCE.uninstallDependencies(pluginId);
        } catch (Throwable t) {
            FileLog.e("Failed to uninstall dependencies for plugin: " + pluginId, t);
        }
        new File(controller.pluginsDir, pluginId + ".py").delete();
        controller.plugins.remove(pluginId);
        NotificationCenter.getGlobalInstance().postNotificationNameOnUIThread(NotificationCenter.pluginsUpdated);
        NotificationCenter.getGlobalInstance().postNotificationNameOnUIThread(NotificationCenter.pluginMenuItemsUpdated);
        restartApp(activity);
    }

    private void disablePluginPref(String pluginId) {
        controller.preferences.edit().putBoolean(PluginsController.PREF_PLUGIN_ENABLED_KEY_PREFIX + pluginId, false).apply();
    }

    private void restartApp(Activity activity) {
        AndroidUtilities.runOnUIThread(() -> {
            if (activity != null) {
                Intent intent = activity.getPackageManager().getLaunchIntentForPackage(activity.getPackageName());
                activity.finishAffinity();
                if (intent != null) {
                    activity.startActivity(intent);
                }
            }
            System.exit(0);
        }, 200L);
    }

    public static void showNotRespondingAlert(final Plugin plugin) {
        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (fragment == null) {
            return;
        }
        final Activity activity = fragment.getParentActivity();
        AlertDialog dialog = new AlertDialog.Builder(activity, fragment.getResourceProvider())
                .setTitle(LocaleController.formatString(R.string.PluginIsNotRespondingAlert, plugin.getName()))
                .setItems(
                        new CharSequence[]{
                                LocaleController.getString(R.string.WaitMore),
                                LocaleController.getString(R.string.Disable),
                                LocaleController.getString(R.string.Delete)
                        },
                        new int[]{R.drawable.msg_recent, R.drawable.msg_block, R.drawable.msg_delete},
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int which) {
                                if (which == 1) {
                                    PluginsController.getInstance().watchdog.forceDisablePlugin(plugin.getId(), activity);
                                } else if (which == 2) {
                                    PluginsController.getInstance().watchdog.forceDeletePlugin(plugin.getId(), activity);
                                }
                            }
                        })
                .create();
        dialog.show();
        dialog.setItemColor(dialog.getItemsCount() - 1, Theme.getColor(Theme.key_text_RedBold), Theme.getColor(Theme.key_text_RedRegular));
    }
}
