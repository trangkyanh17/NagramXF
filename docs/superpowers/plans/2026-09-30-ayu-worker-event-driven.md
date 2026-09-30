# AyuWorker Event-Driven Scheduling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace AyuWorker's permanent 3-second polling loop with one-shot debounced work while preserving Ghost Mode and Last Seen Pill behavior.

**Architecture:** Keep the public static `AyuWorker` API intact. Move worker state/decision logic into a package-private pure-Java coordinator so behavior is deterministic under JUnit, and use a small one-shot scheduler adapter backed by `ScheduledExecutorService.schedule()`.

**Tech Stack:** Java 21, Android/Telegram Java core, JUnit 4.13.2, Gradle 9.4/AGP.

**Spec:** `docs/superpowers/specs/2026-09-30-nagramxf-runtime-efficiency.md`

## Global Constraints
- Base commit is `30dcd6ce7b5b0279fa86f302ca61cd272bfa3068`.
- No Rust, Compose rewrite, Telegram-core rewrite, or plugin/database changes in this patch.
- Preserve `AyuWorker` public method signatures and the 1500 ms debounce delay.
- No production code before a failing regression test.
- Do not claim battery/thermal improvement until measured on a real device.

## Review Focus
- Repeated online events must collapse into one pending one-shot task.
- Multi-account flags must all be consumed by the one scheduled run.
- Clearing one account must not suppress another account's pending send.
- Last Seen refresh must not create a delayed duplicate offline send.
- Shutdown must leave no pending recurring work.

---### Task 1: Pure coordinator behavior

**Files:**
- Create: `TMessagesProj/src/main/java/com/radolyn/ayugram/AyuWorkerCoordinator.java`
- Test: `TMessagesProj/src/test/java/com/radolyn/ayugram/AyuWorkerCoordinatorTest.java`

**Interfaces:**
- `AyuWorkerCoordinator.Scheduler#reschedule(Runnable)` and `#shutdown()`.
- Constructor accepts account count, activation predicate, offline-mode predicate, offline-send consumer, fetch-notify consumer, scheduler.
- Produces `setOnline(int, boolean)`, `clearOnline(int)`, `requestLastSeenUpdate(int)`, `runOnce()`, `shutdown()`.

- [ ] **Step 1: Write failing tests** for one-shot reschedule intent, multi-account consumption, clear isolation, immediate Last Seen refresh without queued duplicate, and shutdown delegation.
- [ ] **Step 2: Run** `TMessagesProj:testNormalDebugUnitTest --tests com.radolyn.ayugram.AyuWorkerCoordinatorTest` and verify RED for the missing coordinator.
- [ ] **Step 3: Implement the minimal coordinator** using per-account `AtomicBoolean` state and no Android dependencies.
- [ ] **Step 4: Re-run the targeted test** and verify GREEN.
- [ ] **Step 5: Run the full unit-test task** and record every failure, including pre-existing failures.

### Task 2: One-shot scheduler adapter

**Files:**
- Create: `TMessagesProj/src/main/java/com/radolyn/ayugram/AyuOneShotScheduler.java`
- Test: `TMessagesProj/src/test/java/com/radolyn/ayugram/AyuOneShotSchedulerTest.java`

**Interfaces:**
- Implements `AyuWorkerCoordinator.Scheduler`.
- `AyuOneShotScheduler(long delayMs)` uses a single scheduled executor.
- `reschedule(Runnable)` cancels the previous not-yet-finished future and calls `schedule`, never `scheduleWithFixedDelay`.
- `shutdown()` cancels pending work and stops the executor.- [ ] **Step 1: Write failing tests** proving a scheduled action executes once, rescheduling replaces the earlier action, cancellation/shutdown prevents pending execution, and no periodic execution occurs.
- [ ] **Step 2: Run the targeted scheduler test** and verify RED.
- [ ] **Step 3: Implement the minimal scheduler adapter** with synchronized pending-future replacement.
- [ ] **Step 4: Re-run the targeted scheduler test** and verify GREEN.

### Task 3: Wire AyuWorker without changing its public API

**Files:**
- Modify: `TMessagesProj/src/main/java/com/radolyn/ayugram/AyuWorker.java`

**Interfaces:**
- Keep `run()`, `requestLastSeenUpdate(int)`, `setOnline(int, boolean)`, `clearOnline(int)`, and `shutdown()` callable exactly as today.
- Instantiate coordinator with `UserConfig.MAX_ACCOUNT_COUNT`, live account/ghost predicates, `sendOffline`, `notifyLastSeenPillFetch`, and a 1500 ms `AyuOneShotScheduler`.

- [ ] **Step 1: Wire the coordinator and remove `PERIOD_MS`, `scheduleWithFixedDelay`, and the direct worker flag map from `AyuWorker`**.
- [ ] **Step 2: Run both targeted test classes** and verify GREEN.
- [ ] **Step 3: Run full `TMessagesProj:testNormalDebugUnitTest`**.
- [ ] **Step 4: Build `TMessagesProj:assembleNormalStaging`** with arm64-focused CI/build settings where supported.
- [ ] **Step 5: Review `git diff` for scope, public API drift, new recurring timers, and accidental changes outside the three production/test files plus docs.**
- [ ] **Step 6: Commit only after fresh verification evidence.**

## Follow-up plans, not part of this branch
1. Plugin watchdog: replace 1-second polling with per-execution timeout tasks.
2. Ayu Room database: remove `allowMainThreadQueries()` and batch hot write paths.
3. Custom executor consolidation: bound AI/transcribe/Ayu background pools.
4. Push fallback: verify 10-minute RTC_WAKEUP necessity and provider exclusivity.
5. GitHub Actions: split fast normal-arm64 CI from plugin/full-ABI release builds.