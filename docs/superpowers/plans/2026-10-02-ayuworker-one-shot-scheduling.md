# AyuWorker One-Shot Scheduling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace AyuWorker's indefinite 3-second fixed-delay scan with a restartable global one-shot while preserving the existing Ghost/offline packet contract.

**Architecture:** Introduce one package-private pure-Java scheduler seam that wraps the existing `ScheduledExecutorService`; `AyuWorker` keeps the same executor, global timer semantics, account flags, packet logic, and shutdown behavior. Only scheduler ownership plus `run()` are changed.

**Tech Stack:** Java 21, `ScheduledExecutorService`, `ScheduledFuture`, JUnit 4.13.2, existing Gradle Android build.

**Spec:** `docs/superpowers/specs/2026-10-02-ayuworker-one-shot-scheduling-design.md`

## Global Constraints

- Implementation base is `5d478ff9ad54fac5b3063ad73892ec488cf934eb`.
- Keep `INITIAL_DELAY_MS = 1500L` and `LAST_SEEN_FETCH_DELAY_MS = 100L` exactly.
- Remove `PERIOD_MS` and `scheduleWithFixedDelay` from `AyuWorker`.
- Reuse the existing single `ScheduledExecutorService`; create no new executor, thread, Handler, repeating task, or per-account timer.
- Keep `run()` synchronized and preserve global cross-account restart/debounce semantics.
- Do not modify Ghost defaults/preferences/schema, packet interception, LastSeenPill, or account lifecycle code.
- Freeze the bodies of `runOnce()`, `shouldSendOffline(int)`, `sendOffline(int)`, `notifyLastSeenPillFetch(int)`, `requestLastSeenUpdate(int)`, `setOnline(int, boolean)`, `clearOnline(int)`, and `shutdown()` against the P2 implementation base.
- Do not merge `dev`, release, install, or start device battery/thermal testing in P3.

## Review Focus

- A second trigger while the previous future is pending must call `cancel(false)` and still schedule exactly one replacement. Covered by Task 1 `pendingRestartCancelsWithoutInterruptAndReplaces`.
- A trigger while the previous action is already running must not interrupt it, and failed `cancel(false)` must not suppress the replacement. Covered by Task 1 `runningTaskCanOverlapReplacementWithoutInterrupt`.
- A completed prior future must not receive a redundant cancel on the next restart. Covered by Task 1 `completedPriorHandleIsNotCancelled`.
- Executing the scheduled action must not create another task by itself. Covered by Task 1 `executingOneShotDoesNotSelfReschedule`.
- Scheduler/backend failure must propagate rather than being reported as a successful restart. Covered by Task 1 `backendFailurePropagates`.

## File Structure

- Create `TMessagesProj/src/main/java/com/radolyn/ayugram/RestartableOneShotScheduler.java`: package-private, pure-Java restart/cancel/schedule policy only.
- Create `TMessagesProj/src/test/java/com/radolyn/ayugram/RestartableOneShotSchedulerTest.java`: deterministic fake scheduler/handle tests; no real clock or sleeping.
- Modify `TMessagesProj/src/main/java/com/radolyn/ayugram/AyuWorker.java`: adapt the existing scheduler to the helper and replace the fixed-delay `run()` implementation only.

---
### Task 1: Pure-Java restartable one-shot scheduler

**Files:**
- Create: `TMessagesProj/src/main/java/com/radolyn/ayugram/RestartableOneShotScheduler.java`
- Test: `TMessagesProj/src/test/java/com/radolyn/ayugram/RestartableOneShotSchedulerTest.java`

**Interfaces:**
- Produce package-private final `RestartableOneShotScheduler`.
- Nested package-private interface `Scheduler { Handle schedule(Runnable action, long delayMs); }`.
- Nested package-private interface `Handle { boolean isDone(); boolean cancel(boolean mayInterruptIfRunning); }`.
- Constructor: `RestartableOneShotScheduler(Scheduler scheduler, long delayMs)`.
- Method: `void restart(Runnable action)`.
- The helper stores only the newest `Handle`; it creates no threads/executors and has no Android/Telegram/Ghost dependencies.

- [ ] **Step 1: Write the failing scheduler tests**

Add tests named:
`firstTriggerSchedulesExactlyOneDelayedTask`,
`pendingRestartCancelsWithoutInterruptAndReplaces`,
`executingOneShotDoesNotSelfReschedule`,
`completedPriorHandleIsNotCancelled`,
`runningTaskCanOverlapReplacementWithoutInterrupt`,
`cancelFalseResultStillSchedulesReplacement`,
`backendFailurePropagates`.
Assertions must pin these exact values/behaviors: `delayMs == 1500L` in the fake scheduler fixture; replacement calls prior `cancel(false)` exactly once; completed prior handle is not cancelled; running/non-done prior handle may return `false` from cancel and replacement still schedules; invoking the captured `Runnable` does not increase schedule count; backend `RuntimeException` escapes `restart()`.

- [ ] **Step 2: Run focused test and confirm RED**

Run with JDK 21/canonical SDK:
`./gradlew :TMessagesProj:testNormalDebugUnitTest --tests 'com.radolyn.ayugram.RestartableOneShotSchedulerTest' --no-configuration-cache`

Expected: FAIL because `RestartableOneShotScheduler` does not exist.

- [ ] **Step 3: Implement the minimal helper**

`restart(Runnable action)` checks the remembered handle; if non-null and not done, call `cancel(false)` and ignore only the boolean return value. Then call `scheduler.schedule(action, delayMs)` and replace the remembered handle with the returned handle. Do not catch backend exceptions and do not wrap/reschedule `action`.

- [ ] **Step 4: Run focused test and confirm GREEN**

Run the command from Step 2. Expected: all seven tests PASS.

- [ ] **Step 5: Verify helper purity and commit Task 1**

Run `git diff --check` and verify the helper imports only `java.*` types, owns no executor/thread/Handler, and contains no account/Ghost/Telegram logic.

Commit: `perf: add restartable one-shot scheduler`

---
### Task 2: Wire one-shot scheduling into `AyuWorker`

**Files:**
- Modify: `TMessagesProj/src/main/java/com/radolyn/ayugram/AyuWorker.java:12-49`
- Consume: `TMessagesProj/src/main/java/com/radolyn/ayugram/RestartableOneShotScheduler.java`
- Test: `TMessagesProj/src/test/java/com/radolyn/ayugram/RestartableOneShotSchedulerTest.java`

**Interfaces:**
- Consume `RestartableOneShotScheduler(Scheduler,long)` and `restart(Runnable)` from Task 1.
- Production `Scheduler` adapter wraps the existing `ScheduledExecutorService.schedule(action, delayMs, TimeUnit.MILLISECONDS)`.
- Production `Handle` adapter delegates `isDone()` and `cancel(boolean)` to the returned `ScheduledFuture<?>`.
- `AyuWorker.run()` remains `public static synchronized void run()` and delegates one delayed `AyuWorker::runOnce` to the helper.

- [ ] **Step 1: Capture the frozen baseline method bodies before editing**

From P2 base `5d478ff9`, extract and retain exact source bodies for:
`runOnce`, `shouldSendOffline`, `sendOffline`, `notifyLastSeenPillFetch`, `requestLastSeenUpdate`, `setOnline`, `clearOnline`, `shutdown`.

Use a brace-aware Python source extractor rather than line-number comparison. Expected: eight method bodies captured from `git show 5d478ff9:TMessagesProj/src/main/java/com/radolyn/ayugram/AyuWorker.java`.
- [ ] **Step 2: Replace fixed-delay ownership with the helper**

In `AyuWorker`:
- remove `PERIOD_MS`;
- keep the existing static single-thread `ScheduledExecutorService scheduler` unchanged;
- replace direct `ScheduledFuture<?> scheduledTask` ownership with one static `RestartableOneShotScheduler` configured with `INITIAL_DELAY_MS`;
- adapt each scheduled future to the helper's `Handle` interface;
- replace the body of synchronized `run()` with `oneShotScheduler.restart(AyuWorker::runOnce)`.

Do not edit any frozen method from Step 1.

- [ ] **Step 3: Run focused scheduler tests and Java compile**

Run:
`./gradlew :TMessagesProj:testNormalDebugUnitTest --tests 'com.radolyn.ayugram.RestartableOneShotSchedulerTest' --no-configuration-cache`

Then run:
`./gradlew :TMessagesProj:compileNormalDebugJavaWithJavac --no-configuration-cache`

Expected: both PASS.

- [ ] **Step 4: Compare frozen packet/flag method bodies against P2**

Use the same brace-aware extractor from Step 1 against current `AyuWorker.java`. Compare each of the eight method bodies byte-for-byte after newline normalization only.
Expected: all eight match P2 exactly.
- [ ] **Step 5: Verify scheduler invariants statically**

Require in `AyuWorker.java`:
- no `scheduleWithFixedDelay`;
- no `PERIOD_MS`;
- exactly one `Executors.newSingleThreadScheduledExecutor()`;
- production helper delay comes from `INITIAL_DELAY_MS == 1500L`;
- `LAST_SEEN_FETCH_DELAY_MS == 100L`;
- no new `Thread`, `Handler`, executor, per-account timer, or recurring scheduler call.

- [ ] **Step 6: Run complete normal-debug unit suite**

Environment: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`, `ANDROID_HOME=/srv/nagramxf-sdk/android-sdk`, `ANDROID_SDK_ROOT=/srv/nagramxf-sdk/android-sdk`.

Run: `./gradlew :TMessagesProj:testNormalDebugUnitTest --no-configuration-cache`
Expected: `BUILD SUCCESSFUL`, zero failures/errors.

- [ ] **Step 7: Review diff and commit Task 2**

Run `git diff --check`. Product diff for Task 2 must be limited to `AyuWorker.java`; no Ghost config/interceptor/UI/lifecycle files may change.

Commit: `perf: make ayu worker scheduling one shot`

---
### Task 3: Whole-P3 regression and invariant gate

**Files:**
- No planned product-code changes; only fix defects exposed by this gate through the owning task's RED→GREEN cycle.

- [ ] **Step 1: Run focused scheduler tests fresh at P3 HEAD**

Run:
`./gradlew :TMessagesProj:testNormalDebugUnitTest --tests 'com.radolyn.ayugram.RestartableOneShotSchedulerTest' --no-configuration-cache`
Expected: PASS.

- [ ] **Step 2: Run full unit suite fresh at P3 HEAD**

Run:
`./gradlew :TMessagesProj:testNormalDebugUnitTest --no-configuration-cache`
Expected: `BUILD SUCCESSFUL`, zero failures/errors.

- [ ] **Step 3: Re-run frozen-body comparison**

Compare all eight frozen `AyuWorker` method bodies against `5d478ff9` with the brace-aware extractor. Expected: exact match after newline normalization.

- [ ] **Step 4: Audit the complete P3 diff from P2**

Inspect `git diff 5d478ff9...HEAD`. Allowed runtime scope is only `RestartableOneShotScheduler.java` plus the scheduling fields/imports/`run()` portion of `AyuWorker.java`; focused test files and approved docs are also allowed.
- [ ] **Step 5: Re-run static scheduler assertions**

Require all of these at final HEAD:
- no `scheduleWithFixedDelay` or `PERIOD_MS` in `AyuWorker`;
- exactly one existing scheduled executor remains;
- no new executor/thread/Handler/recurring/per-account timer;
- one-shot helper contains no Android/Telegram/Ghost/account code;
- helper restart path uses `cancel(false)` only for a non-done prior handle;
- helper does not self-reschedule.

- [ ] **Step 6: Final hygiene and branch verification**

Run `git diff --check`, `git status --short`, `git log --oneline --decorate -4`, and compare local HEAD with `origin/<implementation-branch>` after push.
Expected after implementation commits: working tree clean and local/remote SHA identical.

- [ ] **Step 7: Review P3 as one slice**

Review for global debounce parity, non-interrupting restart behavior, delayed second-offline preservation, retained flag semantics, and absence of new persistent background work. Do not merge `dev`, release, deploy, or start device measurements.

## Execution Gate

Execution mode remains **Native** from the previously approved workflow. After this plan is approved, use `superpowers:executing-plans`; implement Tasks 1→3 sequentially with RED→minimal change→GREEN→broader verification→commit boundaries.

Real-device battery/thermal validation remains deferred to the final optimization-program device gate.
