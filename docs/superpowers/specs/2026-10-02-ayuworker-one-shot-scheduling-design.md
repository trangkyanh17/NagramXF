# P3 — AyuWorker One-Shot Scheduling Design

**Status:** Approved 2026-10-02
**Program:** NagramXF Performance V2
**Golden behavioral baseline:** `30dcd6ce7b5b0279fa86f302ca61cd272bfa3068`
**Implementation base after P2:** `5d478ff9ad54fac5b3063ad73892ec488cf934eb`

## Intent

Remove the indefinite 3-second wakeup loop in `AyuWorker` while preserving the existing Ghost/offline packet timing contract.

This is a feature-path optimization. `sendOfflinePacketAfterOnline` defaults to `false`; therefore P3 does not claim an idle-default-path gain.

The accepted pre-device claim is narrow: after a trigger has been serviced, `AyuWorker` no longer keeps scheduling a scan every 3 seconds when no new trigger occurs.

No battery, thermal, frame-time, or long-session improvement is claimed until final real-device validation.

## Baseline evidence

`AyuWorker` is unchanged between the golden baseline and the P2 implementation head.

Baseline scheduling constants and behavior:

- `INITIAL_DELAY_MS = 1500L`.
- `PERIOD_MS = 3000L`.
- `LAST_SEEN_FETCH_DELAY_MS = 100L`.
- A single static `ScheduledExecutorService` is created with `Executors.newSingleThreadScheduledExecutor()`.
- `run()` cancels the current pending/repeating future when it is not done, then calls `scheduleWithFixedDelay(runOnce, 1500 ms, 3000 ms)`.
- `runOnce()` scans every account and sends offline only when the account is activated and `shouldSendOffline(account)` returns true.
- Once the fixed-delay job is created, it continues waking every 3 seconds until cancelled by another `run()` call or executor shutdown.

The structural problem is the recurring scan after pending offline flags have already been consumed. The executor thread itself is not the P3 target.

## Activation paths

The recurring worker is entered only through `AyuWorker.run()`, which is called internally by:

- `requestLastSeenUpdate(account)` when offline-after-online is enabled;
- `setOnline(account, needOffline)` when offline-after-online is enabled.

Observed callers of `setOnline(...)` include Ghost configuration side effects, read-after-send completion paths, message/send completion wrappers, and Ghost settings UI interactions.

`clearOnline(account)` clears the account flag but does not cancel the global scheduled task.

`shutdown()` exists but has no repository caller. P3 keeps its current semantics and does not redesign worker lifecycle.

## Behavior contract to preserve

### `requestLastSeenUpdate(account)`

When offline-after-online is enabled:

1. set that account's `needOffline` flag to `true`;
2. send an offline status request immediately;
3. schedule/restart the global worker with an exact scheduler delay argument of 1500 ms; actual execution may occur later only because of normal executor scheduling latency;
4. if the account remains activated and the feature remains enabled, the delayed worker consumes the flag and sends offline a second time;
5. each offline send keeps the existing 100 ms `LAST_SEEN_PILL_FETCH` notification behavior.

The second delayed offline packet is intentional baseline behavior for P3. It must not be removed or deduplicated. Repeated `requestLastSeenUpdate()` calls each keep their immediate offline send while restarting the single shared delayed scan.

When offline-after-online is disabled, `requestLastSeenUpdate()` does not set `needOffline` and does not start the worker; it only schedules the existing 100 ms last-seen pill fetch notification.

### `setOnline(account, needOffline)`

Baseline semantics are preserved even for values that appear counterintuitive:

- the account flag is set to the supplied boolean even when offline-after-online is disabled;
- if offline-after-online is disabled, no worker restart occurs;
- if offline-after-online is enabled, the **global** worker is restarted regardless of whether the supplied value is `true` or `false`;
- restarting for one account resets the shared delay for pending flags on other accounts.

P3 does not convert this to per-account scheduling.

### `clearOnline(account)`

`clearOnline()` only clears that account's flag. It does not cancel or reschedule the global worker. A previously scheduled global scan still runs and can service another account.

### Flag consumption rules

`shouldSendOffline(account)` currently checks the feature before consuming the flag.

Therefore P3 must preserve these cases:

- if the feature is disabled when the delayed scan runs, the flag remains unchanged;
- if the account is not activated, `shouldSendOffline()` is not called and the flag remains unchanged;
- if the feature is enabled and the account is activated, the flag is atomically consumed by `getAndSet(false)`;
- a later trigger can service a previously retained flag after the feature/account becomes eligible again.

P3 must not “clean up” retained flags during an ineligible scan.

## Chosen architecture

Replace the indefinite fixed-delay loop with a **global restartable one-shot**.

The existing single-thread `ScheduledExecutorService` remains the execution backend. `AyuWorker.run()` still owns the global restart semantics, but schedules `runOnce()` exactly once after `INITIAL_DELAY_MS` instead of using `scheduleWithFixedDelay`.

A small pure-Java coordination seam will be introduced for deterministic tests, and it must not own a new thread or executor. It only models/calls:

- cancel the previous future when it is still pending/running according to existing `cancel(false)` behavior;
- schedule one new task after 1500 ms;
- remember the newest future;
- allow a later trigger to restart the shared delay.

After the one-shot executes, it schedules nothing by itself.

## Concurrency and restart semantics

`run()` remains synchronized. P3 must not introduce a per-account scheduler or a second executor.

If a trigger arrives while a prior delayed task is still pending, the prior future is cancelled with `cancel(false)` and a new 1500 ms one-shot is scheduled.

If a trigger arrives while the prior task is already executing, `cancel(false)` does not interrupt it; a new one-shot is allowed to coexist as the next pending task. This matches the current non-interrupting restart behavior and must not be strengthened into mutual exclusion that changes packet timing.

A completed one-shot may remain referenced by the helper as its current handle; a later `run()` observes it as done and schedules the next one-shot without cancelling the completed task.

## Explicit non-goals

P3 does not:

- remove the `ScheduledExecutorService` or claim its thread disappears;
- move work to Android main/UI handlers;
- change the 1500 ms delay;
- change the 100 ms last-seen pill notification delay;
- change `runOnce()`, account scanning, `shouldSendOffline()`, or offline request contents;
- deduplicate immediate and delayed offline packets;
- switch to per-account timers;
- alter Ghost defaults, preferences, locks, schema, protocol interception, or packet eligibility;
- redesign `shutdown()` or add executor recreation after shutdown.

## Rejected alternatives

### Per-account one-shots

Rejected because the baseline uses one global timer. A trigger for account B currently resets the delay for pending account A, so independent account timers would change observable timing.

### Main-thread/Handler scheduling

Rejected because it changes the execution context and introduces a new lifecycle/timing dependency for a protocol-sensitive path.

### Immediate-only offline send

Rejected because it removes the delayed second offline packet from `requestLastSeenUpdate()` and repeats the behavior regression found in the previous AYU optimization attempt.

## Test seam

Implementation will expose scheduling policy through a package-private pure-Java helper named `RestartableOneShotScheduler`.

The helper accepts the existing scheduler/backend rather than creating one. Its public-to-package contract is only:

- restart one delayed action;
- cancel the prior non-done future with `cancel(false)`;
- never self-reschedule after execution;
- preserve access to the newest scheduled future for restart decisions.

Production `AyuWorker` remains the owner of account flags, feature checks, offline sends, notification timing, and executor shutdown.

## Required regression coverage

Pure scheduling tests must cover:

1. first trigger schedules exactly one task at 1500 ms;
2. repeated trigger before execution cancels the old future with `false` and schedules one replacement;
3. running the one-shot does not schedule a follow-up task;
4. a completed prior future is not cancelled on the next trigger;
5. a trigger while the prior task is executing schedules the next one-shot without interrupting the running task;
6. a failed `cancel(false)` return value does not prevent scheduling the replacement one-shot;
7. scheduler/backend exceptions are not silently converted into success.

Behavior-parity verification must additionally freeze the existing `AyuWorker` methods below; their bodies are not implementation scope:

- `runOnce()`;
- `shouldSendOffline(int)`;
- `sendOffline(int)`;
- `notifyLastSeenPillFetch(int)`;
- `requestLastSeenUpdate(int)`;
- `setOnline(int, boolean)`;
- `clearOnline(int)`;
- `shutdown()`.

The implementation gate must compare these method bodies against P2/golden behavior rather than re-modeling packet logic in a new abstraction.

## Deterministic scheduler seam

Because the project has no Mockito/Robolectric dependency, the helper must use tiny package-private abstractions rather than test a real clock/thread.

A suitable shape is:

- `Scheduler.schedule(Runnable action, long delayMs) -> Handle`;
- `Handle.isDone() -> boolean`;
- `Handle.cancel(boolean mayInterruptIfRunning) -> boolean`;
- `RestartableOneShotScheduler.restart(Runnable action)`.

The production adapter wraps the existing `ScheduledExecutorService.schedule(...)`. Tests use fake handles/backends and no real sleeping.

The helper must contain no Android, Telegram, Ghost, account, or packet logic.

## Implementation boundary

Expected production changes are limited to:

- removing `PERIOD_MS`;
- replacing `ScheduledFuture<?> scheduledTask` ownership with the restartable one-shot helper;
- adapting the existing scheduler to the helper's tiny backend interface;
- changing `run()` so it restarts one delayed `runOnce()` instead of a fixed-delay loop.

No other production file is in scope for behavior changes in P3. If implementation requires modifying Ghost configuration, packet interception, LastSeenPill, or account lifecycle logic, execution stops and the design must be revisited.

## Verification gate

Before P3 can be committed as complete:

1. focused scheduler tests pass;
2. `:TMessagesProj:testNormalDebugUnitTest` passes fresh on the final diff;
3. Java compilation passes with JDK 21 and the canonical Android SDK;
4. no `scheduleWithFixedDelay` remains in `AyuWorker`;
5. no `PERIOD_MS` remains in `AyuWorker`;
6. the production one-shot delay is exactly `1500L`;
7. `LAST_SEEN_FETCH_DELAY_MS` remains exactly `100L`;
8. no new executor, thread, Handler, repeating task, or per-account timer is introduced;
9. frozen packet/flag method bodies remain behaviorally/textually unchanged;
10. Ghost defaults/preferences/schema and unrelated runtime files are unchanged;
11. `git diff --check` passes and the working tree is clean after commit.

## Success criteria

P3 succeeds when one trigger produces at most one delayed worker scan unless another trigger explicitly restarts scheduling, while all baseline Ghost/offline packet behavior remains intact.

The code-level performance statement after P3 is limited to: **the AyuWorker recurring 3-second fixed-delay scan has been removed**.

P3 does not by itself establish lower battery drain or temperature. Those remain final-program device measurements under matched Ghost-mode workloads.

## Integration policy

P3 is developed and committed independently from P1/P2 history but uses the reviewed P2 head as its integration base. It is not merged into `dev`, released, or installed on a device as part of this subproject.

After this spec is approved, the next artifact is a detailed implementation plan. Product code changes remain blocked until that plan is also reviewed and approved.
