# P6 — Plugin Runtime Scheduling Design

**Status:** Approved 2026-10-03
**Program:** NagramXF Performance V2
**Implementation base after P5:** `877f1353699c5d74af526d80baddd248297153d5`
**Golden behavioral baseline:** `30dcd6ce7b5b0279fa86f302ca61cd272bfa3068`

## Intent

Reduce persistent plugin-runtime scheduling cost without changing plugin feature behavior.

P6 addresses two independently evidenced scheduling costs:

1. `Utilities.pluginsQueue` is created eagerly as a `DispatchQueue`, which immediately starts a thread even when the plugin engine is disabled or the normal flavor has no plugin runtime.
2. `PluginsWatchdog` starts a dedicated scheduled executor and polls every second for the lifetime of an enabled plugin engine, including periods with no plugin code executing.

P6 changes those resources from eager/periodic to demand-driven while preserving plugin execution, freeze detection, safe-mode, lifecycle, hook, and UI contracts.

## Classification

P6 contains:

- **P6A — default-path resource laziness:** make `pluginsQueue` demand-created.
- **P6B — feature-path watchdog scheduling:** replace the 1-second global polling loop with deadline-driven one-shot checks while plugin executions are active.

No battery, power, thermal, or percentage improvement is claimed before device measurement.

Allowed structural claims after verification are limited to:

- no `pluginsQueue` thread is created merely by loading `Utilities`;
- no watchdog fixed-delay polling task runs while no plugin execution needs timeout monitoring;
- a plugin execution exceeding the existing freeze threshold is still detected and cleared under the same behavioral contract.

## Baseline evidence

### Plugin engine defaults

`ExteraConfig.loadConfig()` sets:

```java
pluginsEngine = PluginsController.isPluginEngineSupported()
        && preferences.getBoolean("pluginsEngine", false);
```

The persisted default is therefore disabled.

The normal flavor uses a no-op `PluginsController` whose `isPluginEngineSupported()` returns `false`.

### Eager pluginsQueue

`Utilities` currently declares:

```java
public static volatile DispatchQueue pluginsQueue = new DispatchQueue("pluginsQueue");
```

`DispatchQueue` extends `Thread`, and its default constructor calls `start()` immediately.

Therefore loading `Utilities` starts the plugin queue thread even when:

- the normal flavor cannot run plugins;
- the plugin flavor has `pluginsEngine == false`;
- no plugin task has ever been requested.

The plugin-flavor `PluginsController.runOnPluginsQueue(...)` already contains lazy create/recreate logic.

One direct call site bypasses that helper:

```java
Utilities.pluginsQueue.postRunnable(...)
```

inside `loadPluginSettings(String pluginId)`.

### Watchdog activation

Plugin-flavor `PluginsController.init(...)` returns immediately when the engine is unsupported or disabled.

When enabled it currently:

1. handles native crash state;
2. applies ART options;
3. calls `watchdog.start()`;
4. ensures `pluginsQueue` exists;
5. initializes plugin engines.

`PluginsWatchdog.start()` creates a `newSingleThreadScheduledExecutor()` and schedules:

```java
scheduleWithFixedDelay(watchdogRunnable, 1L, 1L, TimeUnit.SECONDS)
```

so the watchdog wakes once per second while the plugin system is active, even when `executingPlugins` is empty.

### Freeze detection semantics

Current state is keyed by the executing Java thread id.

`onPluginExecutionStarted(pluginId)`:

- ignores null plugin ids;
- records `pluginId` and `System.currentTimeMillis()` for the current thread;
- preserves/clears previously frozen state according to the current same-thread/same-plugin logic.

`onPluginExecutionFinished(pluginId)`:

- only removes the current execution if thread id and plugin id still match;
- removes frozen state for that thread;
- clears the plugin's not-responding flag only when no other frozen execution for that plugin remains;
- posts `pluginIsNotResponding` when a previously frozen plugin becomes responsive.

The polling task marks an execution frozen only when:

```text
now - startTime > 5,000 ms
```

It posts at most one `pluginIsNotResponding` notification for all newly frozen executions observed in one watchdog pass.

`stop()`:

- shuts down the watchdog scheduler;
- clears every frozen plugin's not-responding state;
- clears frozen/executing maps.

### Hook coverage

Watchdog start/finish markers wrap plugin execution across:

- menu-item conditions;
- pre-request hooks;
- post-request hooks;
- update hooks;
- updates hooks;
- send-message hooks;
- Python engine execution paths.

P6 must not remove or relocate those ownership boundaries unless a separate behavior audit proves equivalence.

## Behavior contract to preserve

### Engine-disabled startup

When the plugin engine is disabled:

- no `pluginsQueue` thread is started solely by `Utilities` class initialization;
- `PluginsController.init(...)` still returns without initializing engines;
- watchdog scheduling remains inactive;
- plugin hooks remain no-op through existing `isPluginEngineAvailable()` guards;
- no plugin feature is silently enabled.

P6 does not require removing the existing lightweight plugin lifecycle callback registration from `ApplicationLoader`; that is a separate concern.

### Plugin queue

The plugin queue keeps the same name and `DispatchQueue` type.

The canonical plugin-flavor accessor becomes the only way plugin code schedules work onto this queue.

Required behavior:

- first plugin work creates exactly one live `DispatchQueue("pluginsQueue")`;
- concurrent first callers do not create duplicate queues;
- later work reuses the same live queue;
- if an existing queue is dead, the next plugin work replaces it;
- normal-flavor behavior remains no-op;
- changing queue creation timing must not change plugin task ordering once the queue exists.

P6 does not tear the queue down automatically when the user disables the engine at runtime. That would introduce a separate shutdown/queued-work contract and is intentionally deferred.

### Watchdog start/stop

`start()` remains idempotent.

After P6, `start()` arms watchdog capability but must not create a recurring polling task.

`stop()` remains authoritative and must:

- prevent future watchdog checks from marking executions frozen;
- cancel any scheduled one-shot check;
- shut down any watchdog executor if it exists;
- clear not-responding state using the current logic;
- clear execution maps.

Calling `stop()` when no watchdog executor was ever created must be safe.

### Freeze threshold

No execution may be marked not-responding before:

```text
elapsed > FREEZE_TIMEOUT_MS
```

where `FREEZE_TIMEOUT_MS` remains `5_000L`.

P6 may schedule the first timeout check directly at the first time this predicate can become true rather than waiting for the next global 1-second polling tick.

This narrows detection latency but does not change the threshold predicate, plugin flag, notification type, or recovery contract.

Exact millisecond notification timing is not treated as a stable public API; the stable contract is "not before >5 seconds, then detect without requiring further plugin activity."

### Notification semantics

For a single watchdog check:

- one or more newly frozen executions may be processed;
- at most one `NotificationCenter.pluginIsNotResponding` event is posted for that check.

Finishing a frozen execution retains the existing `clearNotResponding(...)` behavior and notification.

P6 must not spam repeated freeze notifications for an execution already marked frozen.

### Same-thread overwrite behavior

The current watchdog stores only one active execution per Java thread id.

P6 does not redesign nested execution semantics.

A new `onPluginExecutionStarted(...)` on the same thread continues to replace the current `executingPlugins` entry exactly as baseline does.

Timeout scheduling must always validate execution identity before freezing so a stale timeout cannot freeze a replacement execution.

### Safe mode and force actions

The following remain unchanged:

- force-disable plugin preference behavior;
- force-delete behavior;
- app restart behavior;
- safe-mode crash flags;
- not-responding alert actions and UI;
- plugin enable/disable preferences.

## P6A architecture — lazy pluginsQueue

Change `Utilities.pluginsQueue` to a nullable field with no constructor side effect:

```java
public static volatile DispatchQueue pluginsQueue;
```

In plugin-flavor `PluginsController`, centralize queue acquisition in a synchronized/double-checked helper such as:

```java
private static DispatchQueue getOrCreatePluginsQueue()
```

Contract:

1. read `Utilities.pluginsQueue`;
2. if non-null and alive, return it;
3. synchronize on the existing controller class lock;
4. re-check;
5. create `new DispatchQueue("pluginsQueue")` only when absent/dead;
6. publish and return it.

`runOnPluginsQueue(...)` uses this accessor.

The direct `loadPluginSettings(String pluginId)` call must route through `runOnPluginsQueue(...)` rather than dereferencing `Utilities.pluginsQueue` directly.

Plugin `init(...)` may ensure the queue exists because enabled-engine initialization itself is real plugin work.

No other `Utilities` queue is changed.

## P6B architecture — deadline-driven watchdog

Replace fixed-delay polling with at most one scheduled timeout check representing the earliest currently relevant execution deadline.

### Scheduler state

`PluginsWatchdog` keeps its current execution/frozen maps and adds scheduling state protected by a dedicated lock.

Conceptual state:

- `boolean running`;
- nullable `ScheduledThreadPoolExecutor scheduler`;
- nullable `ScheduledFuture<?> scheduledCheck`;
- `long scheduledDeadlineMs`.

The scheduler is created lazily only when at least one active execution requires future timeout checking.

It is configured so cancelled tasks are removed from the queue and its core thread may time out while idle.

There is no `scheduleWithFixedDelay`, fixed-rate task, polling loop, or timer.

### Earliest deadline

For each current execution not already frozen as that exact `ExecutionInfo`, the first eligible freeze time is:

```text
startTime + FREEZE_TIMEOUT_MS + 1 ms
```

The watchdog schedules one one-shot check for the earliest such deadline.

When execution state changes:

- start;
- finish;
- freeze;
- stop;

the watchdog recomputes the earliest relevant deadline.

If no active non-frozen execution needs monitoring, the pending check is cancelled and no new check is scheduled.

### Timeout callback

When the one-shot check fires:

1. verify watchdog is still running;
2. clear the scheduled-check marker;
3. read current time;
4. scan current executions;
5. apply the existing `elapsed > FREEZE_TIMEOUT_MS` predicate;
6. call the existing identity-safe `freezeExecution(threadId, info)`;
7. post at most one not-responding notification if any newly froze;
8. schedule the next earliest remaining deadline, if any.

A stale timeout for an overwritten/finished execution cannot freeze it because `freezeExecution` still requires the same `ExecutionInfo` identity to be current.

### Idle behavior

When the plugin engine is enabled but no plugin execution is active:

- no recurring watchdog task exists;
- no 1-second wakeup occurs;
- the scheduler's worker thread is allowed to time out when idle.

The scheduler object may remain allocated until `stop()`, but no permanent active polling thread is required.

## Concurrency/race requirements

### Execution finishes before timeout

The execution is removed using baseline identity checks.

Any obsolete scheduled check is cancelled/recomputed.

Even if a stale callback races cancellation, `freezeExecution` cannot mark an execution that is no longer current.

### Same thread starts another plugin before old timeout

The new `ExecutionInfo` replaces the old map value as baseline does.

An old timeout callback sees identity mismatch and cannot freeze the replacement.

The next deadline is recomputed from current state.

### Multiple concurrent plugin threads

Only one timeout task is required.

When it fires, all currently overdue executions are evaluated and newly frozen entries are batched into one notification event for that pass.

Then the next earliest active deadline is scheduled.

### stop() races timeout

`stop()` first marks the watchdog not running/cancels scheduler state under the scheduling lock.

A racing callback must re-check running state before flagging new freezes.

Cleanup of frozen plugin state then follows baseline behavior.

### restart()

`PluginsController.restart()` continues to compose existing `shutdown(...)` and `init(...)`.

No special watchdog restart path is added outside existing lifecycle ownership.

## Verification strategy

P6 must test both flavors because plugin runtime code is source-set-specific.

Available Gradle tasks are confirmed:

- `:TMessagesProj:testNormalDebugUnitTest`;
- `:TMessagesProj:testPluginDebugUnitTest`;
- `:TMessagesProj:compileNormalDebugJavaWithJavac`;
- `:TMessagesProj:compilePluginDebugJavaWithJavac`.

### Queue tests

A source/contract test must prove:

- `Utilities.pluginsQueue` has no eager `new DispatchQueue("pluginsQueue")`;
- plugin controller has one canonical lazy queue creator;
- all plugin runtime queue submissions use the canonical helper;
- no direct unsafe `Utilities.pluginsQueue.postRunnable(...)` remains.

Where practical, a pure helper/concurrency test should verify single-instance creation under concurrent requests.

### Watchdog tests

P6 should introduce a deterministic pure-Java scheduling coordinator or injectable clock/scheduler boundary so timeout policy can be tested without sleeping for real seconds.

Required cases:

1. `start()` with no execution schedules no polling work;
2. first execution schedules one deadline;
3. short execution finishing before deadline cancels/recomputes pending work;
4. execution cannot freeze at elapsed `<= 5000`;
5. execution can freeze at elapsed `> 5000`;
6. stale timeout cannot freeze a replaced execution;
7. multiple executions use the earliest deadline;
8. one check can freeze multiple overdue executions while producing one notify decision;
9. already-frozen execution is not repeatedly notified;
10. finishing a frozen execution preserves current clear semantics;
11. `stop()` cancels future work and prevents late freeze;
12. restart can arm scheduling again.

A structural test must reject `scheduleWithFixedDelay` and `scheduleAtFixedRate` in `PluginsWatchdog`.

## Frozen behavior/code boundaries

P6 must not alter:

- Python plugin hook results;
- network/update/send hook return semantics;
- plugin priority/order;
- plugin setting persistence;
- safe-mode persistence;
- force disable/delete behavior;
- plugin install behavior;
- `ApplicationLoader` activity lifecycle event names/order;
- plugin engine enable UI semantics;
- plugin file detection;
- menu item filtering semantics;
- P1–P5 runtime code unrelated to plugin scheduling.

The existing watchdog start/finish call sites around actual plugin execution are frozen unless a test demonstrates a required bug fix; no such bug fix is part of P6.

## Expected runtime files

P6 runtime changes are expected to be limited to:

- `TMessagesProj/src/main/java/org/telegram/messenger/Utilities.java`;
- `TMessagesProj/src/plugin/java/com/exteragram/messenger/plugins/PluginsController.java`;
- `TMessagesProj/src/plugin/java/com/exteragram/messenger/plugins/utils/PluginsWatchdog.java`;
- optionally one small pure-Java package-private scheduling coordinator under the plugin source set.

No normal-flavor `PluginsController` behavior change should be required beyond compiling against the nullable queue field.

If implementation requires changing `ApplicationLoader`, Python plugin execution semantics, plugin UI behavior, or engine internals, stop and revisit the design.

## Explicit non-goals

P6 does not:

- unregister plugin lifecycle callbacks when the engine is off;
- tear down `pluginsQueue` immediately when the engine is toggled off;
- change plugin engine initialization order;
- change the 5-second freeze threshold;
- change same-thread nested execution semantics;
- change safe-mode decisions;
- rewrite plugin hooks;
- replace `DispatchQueue` with a general executor;
- remove watchdog freeze detection;
- change force-disable/delete UI;
- optimize pip/network/install loops;
- optimize Python runtime startup;
- claim device-level power or thermal gains.

Those require separate evidence and contracts.

## Rejected alternatives

### Disable watchdog entirely

Rejected because not-responding detection and recovery UI are user-visible safety behavior.

### Keep 1-second polling but skip map scan when empty

Rejected because the scheduler still wakes every second and the structural issue remains.

### Run watchdog checks on pluginsQueue

Rejected because a frozen plugin may block that same queue; watchdog detection requires an independent execution context.

### Schedule one independent Future per plugin hook execution

Rejected as the primary design because high-frequency short hooks could create excessive delayed-task allocation/cancellation.

The selected design maintains at most one future timeout check for the earliest active deadline.

### Reuse main/UI Handler

Rejected because a plugin may execute/block on the UI thread, which would prevent timely freeze detection.

### Recycle pluginsQueue on engine disable in P6

Deferred because shutdown callbacks and queued plugin work need a separate ownership audit. P6 first removes the unconditional cold-start thread.

## Verification gate

Before P6 can be considered verified:

1. P6 queue/watchdog RED tests are observed before implementation;
2. focused P6 tests pass;
3. `:TMessagesProj:testNormalDebugUnitTest --no-configuration-cache` passes fresh;
4. `:TMessagesProj:testPluginDebugUnitTest --no-configuration-cache` passes fresh;
5. both normal/plugin Java compile tasks pass with JDK 21;
6. static gate proves `Utilities.pluginsQueue` is no longer eagerly constructed;
7. static gate proves no direct plugin queue dereference bypasses the canonical helper;
8. static gate proves `PluginsWatchdog` contains no fixed-delay/fixed-rate polling;
9. freeze timeout remains `5_000L`;
10. watchdog execution start/finish call-site inventory matches P5;
11. plugin engine default remains disabled;
12. normal-flavor plugin controller remains no-op;
13. no ApplicationLoader/plugin UI/plugin hook semantic diff exists;
14. P1–P5 protected runtime files remain unchanged;
15. `git diff --check` passes;
16. implementation branch is pushed and local/remote HEAD match.

## Success criteria

P6 succeeds when:

- plugin-disabled startup no longer creates the dedicated `pluginsQueue` thread simply through `Utilities` initialization;
- an enabled but idle plugin runtime no longer runs a 1-second watchdog polling loop;
- active plugin execution still receives independent >5-second freeze detection;
- stale timeout work cannot freeze a completed/replaced execution;
- existing not-responding clear/notification behavior remains intact;
- both normal and plugin build variants pass regression verification.

The strongest accepted pre-device statement is:

> Plugin scheduling resources are demand-driven: the pluginsQueue is created only for real plugin work, and the watchdog uses one-shot execution deadlines instead of a permanent 1-second polling loop while preserving the existing freeze threshold and recovery semantics.

## Integration policy

P6 is developed from verified P5 HEAD and committed independently.

This design phase does not authorize implementation, merge into `dev`, release, APK installation, or device performance claims.

After design approval, a separate implementation plan will be written and presented for Plan Gate approval.
