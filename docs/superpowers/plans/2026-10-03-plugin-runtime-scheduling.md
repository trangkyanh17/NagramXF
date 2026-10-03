# P6 — Plugin Runtime Scheduling Implementation Plan

**Status:** Draft — awaiting plan approval
**Design:** `docs/superpowers/specs/2026-10-03-plugin-runtime-scheduling-design.md`
**Implementation base:** `877f1353699c5d74af526d80baddd248297153d5`
**Design commit:** `d74b0e6336479f3bbfd75ce43eb32f60262cb6e4`

## Goal

Make plugin scheduling resources demand-driven without changing plugin behavior:

- `pluginsQueue` must not start merely because `Utilities` is loaded;
- `PluginsWatchdog` must stop waking every second while idle;
- >5-second plugin freeze detection, recovery, notifications, safe mode, and hook semantics remain intact.

P6 is split into two implementation tracks:

- **P6A:** lazy plugin queue;
- **P6B:** deadline-driven watchdog.

Implementation must verify both `normal` and `plugin` build flavors.

## Runtime scope

Expected runtime files:

- `TMessagesProj/src/main/java/org/telegram/messenger/Utilities.java`
- `TMessagesProj/src/plugin/java/com/exteragram/messenger/plugins/PluginsController.java`
- `TMessagesProj/src/plugin/java/com/exteragram/messenger/plugins/utils/PluginsWatchdog.java`
- optionally one package-private pure-Java watchdog scheduling helper under `src/plugin/java/com/exteragram/messenger/plugins/utils/`

Expected tests may be added under:

- `TMessagesProj/src/test/java/com/exteragram/messenger/plugins/`
- `TMessagesProj/src/test/java/com/exteragram/messenger/plugins/utils/`

Forbidden scope without returning to design:

- `ApplicationLoader` runtime changes;
- `PluginsActivity` runtime changes;
- Python plugin hook semantics;
- network/update/send hook result/order changes;
- plugin install/delete semantics;
- safe-mode persistence changes;
- P1–P5 runtime changes unrelated to P6.

## Task 1 — P6A lazy pluginsQueue

### RED

Add a source-contract test first, for example:

`PluginsQueueLazyShapeTest.java`

Required failing assertions on P5 baseline:

1. `Utilities.pluginsQueue` is declared without eager `new DispatchQueue("pluginsQueue")`;
2. plugin `PluginsController` owns one canonical lazy queue creator;
3. lazy creator:
   - returns a live existing queue;
   - synchronizes before creation;
   - rechecks inside the lock;
   - creates exactly `new DispatchQueue("pluginsQueue")` when absent/dead;
4. `runOnPluginsQueue(...)` posts through that creator;
5. `loadPluginSettings(String)` routes through `runOnPluginsQueue(...)`;
6. no direct `Utilities.pluginsQueue.postRunnable(...)` remains in plugin runtime code.

Run:

```bash
./gradlew :TMessagesProj:testPluginDebugUnitTest \
  --tests 'com.exteragram.messenger.plugins.PluginsQueueLazyShapeTest' \
  --no-configuration-cache
```

Record RED before runtime changes.

### GREEN

Change:

```java
public static volatile DispatchQueue pluginsQueue = new DispatchQueue("pluginsQueue");
```

to a nullable declaration with no constructor side effect.

In plugin-flavor `PluginsController` add one canonical helper, conceptually:

```java
private static DispatchQueue getOrCreatePluginsQueue()
```

Behavior:

- return current live queue if present;
- otherwise synchronize on `PluginsController.class`;
- recheck;
- create/publish one queue only when absent/dead.

Update:

- `runOnPluginsQueue(...)` to use the helper;
- `loadPluginSettings(String)` to use `runOnPluginsQueue(...)`;
- `init(...)` to ensure the queue only because enabled-engine initialization is real plugin work.

Do not change any other `Utilities` queue.

### Verify

Run focused plugin-flavor test and:

```bash
./gradlew :TMessagesProj:compilePluginDebugJavaWithJavac --no-configuration-cache
./gradlew :TMessagesProj:compileNormalDebugJavaWithJavac --no-configuration-cache
git diff --check
```

### Commit

```text
perf: lazily create plugins queue
```

## Task 2 — P6B deterministic watchdog scheduling policy

### RED

Before changing `PluginsWatchdog`, add deterministic tests for deadline policy.

Preferred implementation is a small package-private pure-Java helper, e.g.:

`PluginsWatchdogSchedulerState`

It must not depend on Android, Telegram, plugin UI, executors, or wall-clock sleeping.

Required RED cases:

1. starting watchdog capability with no execution produces no deadline;
2. first execution produces one earliest deadline;
3. second later execution does not replace an earlier deadline;
4. earlier second execution moves the deadline earlier;
5. finish removes the execution and recomputes deadline;
6. elapsed `<= 5000` is not overdue;
7. elapsed `> 5000` is overdue;
8. replacing an execution on the same thread invalidates the old identity;
9. already-frozen identity is excluded from next-deadline scheduling;
10. stopping/clearing leaves no pending execution deadline.

Use synthetic timestamps only; no `Thread.sleep`.

Run only the new helper tests and observe RED.

### GREEN

Add the minimal pure-Java helper.

The helper should model only:

- active execution identities;
- frozen/current identity distinction;
- earliest eligible deadline;
- timeout threshold comparison.

It must not own actual threads, Android handlers, notifications, or plugin objects.

Keep `FREEZE_TIMEOUT_MS = 5_000L` authoritative in watchdog or pass it explicitly without changing the value.

### Verify

Run helper tests until GREEN and ensure the helper imports only `java.*`.

### Commit

```text
perf: add plugin watchdog deadline policy
```

## Task 3 — Replace fixed-delay watchdog polling

### RED structural test

Add a watchdog integration/shape test that requires:

- no `scheduleWithFixedDelay`;
- no `scheduleAtFixedRate`;
- `start()` does not schedule periodic work;
- scheduler is created lazily only when execution monitoring needs it;
- at most one scheduled timeout future is tracked;
- `onPluginExecutionStarted(...)` schedules/recomputes earliest deadline;
- `onPluginExecutionFinished(...)` cancels/recomputes stale deadline;
- callback rechecks watchdog running state;
- callback uses existing `freezeExecution(threadId, info)` identity protection;
- one timeout pass batches freeze notification into at most one `pluginIsNotResponding` post;
- `stop()` cancels the pending future, shuts down scheduler, clears state;
- scheduler worker can time out while idle.

Confirm RED against the current fixed-delay implementation.

### GREEN runtime changes

Refactor `PluginsWatchdog` only within the approved design.

Required scheduler state:

- scheduling lock;
- `running` flag;
- nullable scheduled executor;
- nullable scheduled future;
- scheduled deadline marker.

Use a single-thread `ScheduledThreadPoolExecutor` configured to:

- remove cancelled tasks;
- allow core-thread timeout;
- avoid fixed-delay/fixed-rate polling.

### start()

`start()`:

- remains idempotent;
- marks watchdog running;
- does not create a recurring task;
- does not create an executor until an active execution needs a timeout check.

### execution start

After baseline map/frozen-state bookkeeping:

- recompute earliest deadline;
- schedule exactly one one-shot check if needed.

Preserve the existing same-thread overwrite behavior.

### execution finish

After baseline identity-safe removal and frozen-clear behavior:

- recompute/cancel the next deadline.

Do not change `clearNotResponding(...)` semantics.

### timeout callback

One-shot callback:

1. exits if watchdog is no longer running;
2. clears its scheduled marker;
3. reads current time;
4. evaluates all current executions;
5. only freezes when `elapsed > FREEZE_TIMEOUT_MS`;
6. calls existing identity-safe `freezeExecution(...)`;
7. posts at most one `pluginIsNotResponding` notification if any newly froze;
8. recomputes/schedules the next earliest deadline.

A stale callback must not freeze a finished/replaced execution.

### stop()

Under scheduler coordination:

- set running false;
- cancel pending future;
- shutdown executor if created;
- clear scheduler references.

Then preserve existing baseline cleanup:

- reset not-responding flags for frozen plugins;
- clear `frozenExecutions`;
- clear `executingPlugins`.

Calling stop before any scheduler creation must be safe.

### Focused verification

Run focused helper + shape tests under plugin flavor.

Also confirm watchdog start/finish call-site inventory has not changed relative to P5.

### Commit

```text
perf: make plugin watchdog deadline driven
```

## Task 4 — Whole-P6 regression and preservation gate

Run all commands fresh.

### Normal flavor

```bash
./gradlew :TMessagesProj:testNormalDebugUnitTest --no-configuration-cache
./gradlew :TMessagesProj:compileNormalDebugJavaWithJavac --no-configuration-cache
```

Both must be `BUILD SUCCESSFUL`.

### Plugin flavor

```bash
./gradlew :TMessagesProj:testPluginDebugUnitTest --no-configuration-cache
./gradlew :TMessagesProj:compilePluginDebugJavaWithJavac --no-configuration-cache
```

Both must be `BUILD SUCCESSFUL`.

### Static gate against P5

Use a deterministic script against base `877f1353699c5d74af526d80baddd248297153d5`.

It must prove:

1. `ExteraConfig.pluginsEngine` still defaults from `preferences.getBoolean("pluginsEngine", false)`;
2. normal-flavor `PluginsController.isPluginEngineSupported()` still returns false;
3. `Utilities.pluginsQueue` has no eager constructor;
4. exactly one canonical plugin queue creator owns `new DispatchQueue("pluginsQueue")`;
5. no direct `Utilities.pluginsQueue.postRunnable(...)` remains in plugin runtime;
6. no `scheduleWithFixedDelay` or `scheduleAtFixedRate` remains in `PluginsWatchdog`;
7. `FREEZE_TIMEOUT_MS` remains exactly `5_000L`;
8. `freezeExecution(...)`, `clearNotResponding(...)`, force-disable/delete, restart-app behavior are body-identical to P5 unless a narrowly required scheduling-only line changed;
9. watchdog start/finish call-site counts and surrounding hook ownership match P5;
10. `ApplicationLoader.java` SHA-256 matches P5;
11. `PluginsActivity.java` SHA-256 matches P5;
12. Python hook engine files are unchanged from P5;
13. P1–P5 protected runtime files are unchanged;
14. runtime diffs are limited to approved P6 files;
15. no new periodic loop/timer exists;
16. `git diff --check` passes.

### Review

Inspect the full runtime diff manually for:

- hook ordering changes;
- notification changes;
- safe-mode changes;
- queue lifecycle expansion;
- unrelated formatting churn.

Any such diff is a stop condition.

## Task 5 — Record evidence and publish implementation branch

After Task 4 passes:

- mark checklist items complete;
- set plan status to `Executed — verified 2026-10-03`;
- commit verification evidence;
- push:

```text
perf/v2-p6-plugin-runtime-scheduling
```

Verify:

- local HEAD == remote HEAD;
- branch is based on P5 + approved P6 design/plan only;
- no merge into `dev`;
- no release;
- no APK installation;
- no device performance claim.

## Execution checklist

- [ ] P6A RED queue test observed.
- [ ] P6A lazy queue implementation GREEN.
- [ ] P6A plugin compile passed.
- [ ] P6A normal compile passed.
- [ ] P6A committed.
- [ ] P6B helper RED tests observed.
- [ ] P6B deterministic deadline helper GREEN.
- [ ] P6B helper committed.
- [ ] Watchdog integration RED observed.
- [ ] Deadline-driven watchdog GREEN.
- [ ] Fixed-delay/fixed-rate scheduling removed.
- [ ] Watchdog hook ownership inventory preserved.
- [ ] Watchdog runtime committed.
- [ ] Normal full unit suite passed fresh.
- [ ] Normal Java compile passed fresh.
- [ ] Plugin full unit suite passed fresh.
- [ ] Plugin Java compile passed fresh.
- [ ] Whole-P6 static preservation gate passed.
- [ ] Plugin engine default still false.
- [ ] ApplicationLoader unchanged from P5.
- [ ] PluginsActivity unchanged from P5.
- [ ] No unrelated runtime diff.
- [ ] git diff --check passed.
- [ ] Implementation branch pushed.
- [ ] Local/remote implementation HEAD match.
- [ ] No integration/release/deploy/device gate crossed.

## Stop conditions

Stop and return to design if any implementation requires:

- changing plugin hook return/order semantics;
- changing `ApplicationLoader` plugin lifecycle behavior;
- changing PluginsActivity toggle behavior;
- changing safe-mode persistence;
- removing watchdog freeze detection;
- changing the 5-second threshold;
- moving watchdog checks onto UI or `pluginsQueue`;
- adding per-execution scheduled futures as the primary architecture;
- recycling plugin queue on engine disable;
- changing Python runtime/plugin installation behavior;
- touching unrelated P1–P5 runtime files.

## Approval boundary

This document is an implementation plan, not implementation approval.

Plan approval authorizes Tasks 1–5 on the dedicated P6 implementation branch.

It does **not** authorize merge into `dev`, release, APK installation, production/device rollout, or performance claims.
