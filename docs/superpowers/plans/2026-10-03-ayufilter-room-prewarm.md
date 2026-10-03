# P4B — AyuFilter Room Prewarm Implementation Plan

**Status:** Executed — verified 2026-10-03
**Design:** `docs/superpowers/specs/2026-10-03-ayufilter-room-prewarm-design.md`
**Implementation base:** `cc1baf7be4a35f8ee4ccfdd26d477279a6dd7b2d`
**Design commit:** `7a8f1894cc9b254a2496dbe9beeb21ff765dbfac`

## Goal

Implement feature-gated background prewarming for the three AyuFilter Room-backed caches so that, when prewarm completes before first use, normal filter hot paths reuse warm in-memory state instead of doing first-load Room work.

Correctness remains baseline-first:

- no incomplete-data answer;
- no fail-open/fail-closed placeholder state;
- no UI waiting on a latch/future;
- synchronous getters remain the fallback;
- filter-disabled behavior remains effectively zero-cost;
- no new persistent worker/thread/executor/timer/polling resource.

## Files expected to change

Runtime:

- `TMessagesProj/src/main/java/tw/nekomimi/nekogram/filters/AyuFilter.java`
- `TMessagesProj/src/main/java/tw/nekomimi/nekogram/filters/AyuFilterPrewarmCoordinator.java` — new pure-Java helper
- `TMessagesProj/src/main/java/org/telegram/messenger/ApplicationLoader.java`
- `TMessagesProj/src/main/java/tw/nekomimi/nekogram/filters/RegexFiltersSettingActivity.java`

Tests:

- `TMessagesProj/src/test/java/tw/nekomimi/nekogram/filters/AyuFilterPrewarmCoordinatorTest.java` — new
- `TMessagesProj/src/test/java/tw/nekomimi/nekogram/filters/AyuFilterPrewarmShapeTest.java` — new
- existing P4A tests remain unchanged unless a test-only compatibility adjustment is required and justified.

Frozen / forbidden runtime scope:

- no DAO changes;
- no Room schema/entity/migration changes;
- no `AyuFilterCache.java` changes;
- no `DialogCell`, `ChatMessageCell`, or `DownloadController` changes;
- no regex matching behavior changes;
- no global removal of `allowMainThreadQueries()`.

## Task 1 — Pure generation/scheduling coordinator

### Red

Create `AyuFilterPrewarmCoordinatorTest.java` first.

Required failing cases:

1. disabled request returns no token/job;
2. enabled first request returns a token for generation 0;
3. second request in the same generation is coalesced;
4. `invalidate()` advances generation and permits a new schedule;
5. stale generation reports non-current after invalidation;
6. failure for the current scheduled generation reopens scheduling;
7. failure for an old generation cannot disturb the current generation;
8. successful/current schedule remains coalesced until invalidation.

Run only this test and confirm RED because the coordinator does not exist:

```bash
./gradlew :TMessagesProj:testNormalDebugUnitTest \
  --tests 'tw.nekomimi.nekogram.filters.AyuFilterPrewarmCoordinatorTest' \
  --no-configuration-cache
```

### Green

Add package-private `AyuFilterPrewarmCoordinator`.

Planned state:

- `long generation`;
- `long scheduledGeneration = -1`.

Planned synchronized API:

- `long trySchedule(boolean enabled)`
  - returns `-1` when disabled;
  - returns `-1` when current generation is already scheduled;
  - otherwise records and returns current generation.
- `long invalidate()`
  - increments generation;
  - clears scheduled marker;
  - returns new generation.
- `boolean isCurrent(long token)`
  - true only for current generation.
- `void onFailure(long token)`
  - clears scheduled marker only when the failing token is still the current scheduled generation.

There is deliberately no behavioral “ready” bit. The coordinator only controls scheduling/coalescing; filtering correctness remains owned by ordinary synchronous getters.

Run focused test until GREEN.

### Verify

- helper imports only `java.*`;
- no Android/Telegram/Room reference;
- no thread/executor/timer creation;
- `git diff --check`.

### Commit

```text
perf: add ayufilter prewarm coordinator
```

## Task 2 — AyuFilter prewarm entry point and invalidation integration

### Red

Create `AyuFilterPrewarmShapeTest.java` before runtime edits.

Shape assertions must require:

- one static coordinator holder/instance;
- public/package-visible `schedulePrewarmIfEnabled()`;
- disabled guard checks `NaConfig.INSTANCE.getRegexFiltersEnabled().Bool()`;
- scheduling posts to existing `Utilities.globalQueue`;
- no new `Thread`, `DispatchQueue`, executor, Handler, timer, polling, or coroutine;
- background path calls all three existing cache owners:
  - shared filter cache;
  - chat filter cache;
  - shared-exclusion snapshot;
- generation/current checks occur between preload stages;
- ordinary `getRegexFilters()` and `getChatFilterEntries()` synchronous fallback remain present;
- `rebuildCache()` invalidates the coordinator before scheduling the new generation;
- each shared-exclusion invalidation advances generation and schedules afterward;
- `invalidateFilteredCache()` does not advance the preload generation, because it only clears message-result LRU state.

Focused test should fail before runtime implementation.

### Green

Modify `AyuFilter.java` minimally.

Add import:

- `org.telegram.messenger.Utilities`.

Add a coordinator holder beside the P4A exclusion snapshot holder.

Implement:

```java
public static void schedulePrewarmIfEnabled()
```

Caller-side work must be limited to:

1. read regex-enabled flag;
2. ask coordinator for a generation token;
3. if token < 0, return;
4. post one runnable to `Utilities.globalQueue`.

Background routine:

1. check token is still current;
2. call `getRegexFilters()`;
3. check token again;
4. call `getChatFilterEntries()`;
5. check token again;
6. call the existing shared-exclusion snapshot path using a no-op/missing dialog key only to force the full exclusion map snapshot to load;
7. final current-token check;
8. on an uncaught exception, log and call `coordinator.onFailure(token)`.

Do **not** wait for the runnable anywhere.

Do **not** change the bodies/return semantics of:

- `loadSharedFilters()`;
- `loadChatFilterEntries()`;
- `getExcludedFilterEntries()`.

### Generation invalidation

In `rebuildCache()`:

- keep existing cache invalidation under `cacheLock`;
- invalidate prewarm generation in the same authoritative invalidation section;
- leave UI notification ordering unchanged;
- after releasing the lock, call `schedulePrewarmIfEnabled()`.

For shared-exclusion add/remove/filter-removal invalidations:

- preserve DAO write first;
- under existing invalidation lock, invalidate P4A shared snapshot + message cache + P4B generation;
- after the lock, schedule prewarm if enabled.

Do not schedule from `invalidateFilteredCache()`.

### Race rule

The implementation must not add a second data store for filter truth.

Existing synchronized cache publication remains authoritative. Generation checks prevent obsolete background work from continuing across stages, while existing cache locks/invalidation ensure a mutation clears any old publication that raced it.

### Focused verification

Run:

```bash
./gradlew :TMessagesProj:testNormalDebugUnitTest \
  --tests 'tw.nekomimi.nekogram.filters.AyuFilterPrewarmCoordinatorTest' \
  --tests 'tw.nekomimi.nekogram.filters.AyuFilterPrewarmShapeTest' \
  --tests 'tw.nekomimi.nekogram.filters.AyuFilterExclusionSnapshotsTest' \
  --tests 'tw.nekomimi.nekogram.filters.AyuFilterDialogSnapshotShapeTest' \
  --tests 'tw.nekomimi.nekogram.filters.AyuFilterSharedSnapshotShapeTest' \
  --no-configuration-cache
```

Then:

```bash
./gradlew :TMessagesProj:compileNormalDebugJavaWithJavac --no-configuration-cache
```

### Static preservation checks

Compare against P4A HEAD and require:

- `isFiltered(...)` guard and exclusion-before-LRU ordering unchanged;
- `loadSharedFilters()` body unchanged;
- `loadChatFilterEntries()` body unchanged;
- `getExcludedFilterEntries()` body unchanged;
- `AyuFilterCache.java` SHA-256 unchanged from P4A;
- no DAO/schema/migration file changed.

### Commit

```text
perf: prewarm ayufilter room caches
```

## Task 3 — Startup and enable-transition triggers

### Red

Extend `AyuFilterPrewarmShapeTest.java` or add a dedicated startup shape test requiring:

- `ApplicationLoader.postInitApplication()` calls `AyuFilter.schedulePrewarmIfEnabled()`;
- that call appears after `NaConfig.init()`;
- startup does not call `getRegexFilters()`, `getChatFilterEntries()`, or Room DAO directly;
- `RegexFiltersSettingActivity` schedules prewarm on an explicit false→true regex-filter enable transition;
- disabling does not force a preload;
- existing `AyuFilter.invalidateFilteredCache()` callback behavior remains.

Confirm RED.

### Green — ApplicationLoader

Add only the necessary `AyuFilter` import and one call:

```text
NaConfig.init();
AyuFilter.schedulePrewarmIfEnabled();
```

The method itself owns the enabled check. Do not duplicate Room/cache loading in `ApplicationLoader`.

### Green — settings enable transition

In the settings callback:

- detect the `RegexFiltersEnabled` config key;
- only when `newValue == true`, call `AyuFilter.schedulePrewarmIfEnabled()`;
- keep the existing `ignoreBlocked` auto-enable behavior;
- keep the existing `AyuFilter.invalidateFilteredCache()` call.

If `ignoreBlocked` auto-enables regex filtering, schedule prewarm after the flag is set true.

No new delayed callback, timer, or background owner.

### Verification

Run focused P4B + P4A tests and Java compile again.

Inspect source ordering explicitly:

- `NaConfig.init()` precedes prewarm schedule;
- schedule call precedes account/controller-heavy startup work;
- settings schedule occurs only on enabled state.

### Commit

```text
perf: trigger ayufilter prewarm on enable
```

## Task 4 — Whole-P4B regression and preservation gate

Run fresh, not relying on previous Gradle task results.

### Full unit suite

```bash
./gradlew :TMessagesProj:testNormalDebugUnitTest --no-configuration-cache
```

Expected: BUILD SUCCESSFUL.

### Java compile

```bash
./gradlew :TMessagesProj:compileNormalDebugJavaWithJavac --no-configuration-cache
```

Expected: BUILD SUCCESSFUL.

### Static gate script

Use a deterministic script to assert all of the following against P4A base `cc1baf7b...`:

- `RegexFilters` default remains `false`;
- `isFiltered(...)` feature-disabled guard remains before cache/Room use;
- dialog exclusion remains before `AyuFilterCache.get(...)`;
- frozen loader bodies match P4A:
  - `loadSharedFilters()`;
  - `loadChatFilterEntries()`;
  - `getExcludedFilterEntries()`;
- `AyuFilterCache.java` SHA-256 matches P4A;
- no DAO/entity/database migration file changed;
- no `DialogCell`, `ChatMessageCell`, or `DownloadController` change;
- no new `Thread`, `DispatchQueue`, executor, HandlerThread, timer, polling, or coroutine construct in P4B runtime files;
- only expected runtime files changed;
- coordinator remains pure Java;
- startup call is after `NaConfig.init()`;
- all three mutation trigger classes advance generation and schedule after invalidation;
- `invalidateFilteredCache()` remains preload-generation neutral.

### Diff hygiene

Run:

```bash
git diff --check
git status --short
```

Review the full diff for unrelated refactors or formatting churn.

### Plan evidence

Update this plan checklist only after every corresponding command/check has fresh evidence.

Do not mark the task complete from code inspection alone.

### Final implementation commit if verification-only adjustments are needed

Only if test/static-gate files require an additional legitimate adjustment:

```text
test: verify ayufilter room prewarm
```

Otherwise do not create an empty/artificial commit.

## Task 5 — Publish verified implementation branch

Implementation branch name:

```text
perf/v2-p4b-filter-room-prewarm
```

It must be created from the approved design/plan branch HEAD after the Plan Gate is approved.

Before push:

- working tree clean;
- local HEAD contains all focused commits;
- full suite passed fresh;
- Java compile passed fresh;
- static gate passed;
- `git diff --check` passed.

Push the implementation branch.

Verify:

- local HEAD == remote HEAD;
- branch is based on P4A + approved P4B design/plan only;
- no merge into `dev`;
- no release;
- no APK installation;
- no device performance claim.

## Execution checklist

- [x] Task 1 RED test observed.
- [x] Task 1 coordinator GREEN.
- [x] Task 1 focused verification passed.
- [x] Task 1 committed.
- [x] Task 2 RED integration shape test observed.
- [x] Task 2 AyuFilter prewarm implementation GREEN.
- [x] Task 2 P4A regression tests passed.
- [x] Task 2 Java compile passed.
- [x] Task 2 static preservation checks passed.
- [x] Task 2 committed.
- [x] Task 3 startup/settings RED observed.
- [x] Task 3 startup/settings GREEN.
- [x] Task 3 focused regression passed.
- [x] Task 3 committed.
- [x] Task 4 full unit suite passed fresh.
- [x] Task 4 Java compile passed fresh.
- [x] Task 4 whole-P4B static gate passed.
- [x] Task 4 diff hygiene passed.
- [x] Task 5 implementation branch pushed.
- [x] Local/remote implementation HEAD match.
- [x] No integration/release/deploy/device gate crossed.

## Stop conditions

Stop implementation and return to design if any of these become necessary:

- returning an incomplete filter answer while preload is pending;
- blocking a UI caller waiting for background preload;
- changing DAO/schema/migrations;
- changing regex/filter decision semantics;
- changing DownloadController/cell behavior;
- introducing a new persistent worker or polling mechanism;
- removing `allowMainThreadQueries()` globally;
- changing legacy migration/fallback semantics;
- broad refactoring outside the four expected runtime files.

## Approval boundary

This document is an implementation plan, not implementation approval.

Plan approval authorizes Tasks 1–5 on the dedicated P4B implementation branch. It does **not** authorize merge into `dev`, release, APK installation, production/device rollout, or final performance claims.
