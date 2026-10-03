# AyuFilter Hot-Path Cache Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Remove repeated excluded-dialog JSON parsing and internal shared-exclusion defensive-copy allocation from warm regex-filter paths without changing filter results or cold Room-loading behavior.

**Architecture:** Add one package-private pure-Java `AyuFilterExclusionSnapshots` helper with immutable dialog/shared snapshots. Integrate it into `AyuFilter` in two separate TDD slices: raw-config dialog snapshots first, then shared-exclusion read-only views; keep P4B Room isolation out of scope.

**Tech Stack:** Java 21, JUnit 4.13.2, existing Gson/NaConfig/Room code, Gradle Android unit-test task.

**Spec:** `docs/superpowers/specs/2026-10-02-ayufilter-hot-path-cache-design.md`

## Global Constraints

- Implementation base behavior is P3 HEAD `73b7c6436b9625741c858ddd4f4ea1deb4f3c46b`.
- `RegexFilters` default remains `false`.
- Keep `isDialogExcluded(...)` before `AyuFilterCache.get(...)`.
- Keep Room loaders, DAO/schema/migrations, regex semantics, LRU sizes/keys, config keys and JSON format unchanged.
- No new thread, executor, Handler, timer, polling loop, coroutine, queue, or async callback.
- Parse failures are not cached and remain retryable.
- Public `getExcludedSharedFilterIds(long)` remains a mutable defensive copy.
- P4B first-use Room isolation is not implemented here.
- Device battery/thermal/jank claims remain deferred.

## Review Focus

- Same malformed dialog-config raw value on two calls: first failure must not poison the second retry. Covered by Task 1.
- Config raw string changes outside `setDialogExcluded`: next lookup must reload without explicit invalidation. Covered by Task 1 and Task 2 shape gate.
- Caller mutates collections after publish/load: cached snapshots must remain unchanged. Covered by Task 1.
- Invalidation races an in-flight first shared load: invalidation must win for the next lookup. Covered by Task 1.
- Public shared-ID copy is mutated by a caller: internal read-only view and future copies must remain unchanged. Covered by Task 1 and Task 3.

---
### Task 1: Pure-Java exclusion snapshot owner

**Files:**
- Create: `TMessagesProj/src/main/java/tw/nekomimi/nekogram/filters/AyuFilterExclusionSnapshots.java`
- Test: `TMessagesProj/src/test/java/tw/nekomimi/nekogram/filters/AyuFilterExclusionSnapshotsTest.java`

**Interfaces:**
- Produce package-private final `AyuFilterExclusionSnapshots`.
- `Set<Long> dialogs(String serialized, Function<String, Set<Long>> loader)`.
- `void publishDialogs(String serialized, Collection<Long> values)`.
- `void invalidateDialogs()`.
- `Set<String> sharedView(long dialogId, Supplier<Map<Long, ? extends Set<String>>> loader)`.
- `HashSet<String> sharedCopy(long dialogId, Supplier<Map<Long, ? extends Set<String>>> loader)`.
- `void invalidateShared()`.
- `void invalidateAll()`.
- Returned internal views are unmodifiable; `sharedCopy` is mutable and independent.

- [x] **Step 1: Write the failing dialog snapshot tests**

Add tests:
`firstDialogLookupLoadsOnce`,
`sameRawReusesSameSnapshotWithoutReload`,
`changedRawReloadsBeforeAnswering`,
`invalidateDialogsForcesReload`,
`publishDialogsCopiesCallerInput`,
`dialogLoaderFailureIsNotCached`,
`dialogSnapshotIsReadOnly`,
`nullLoadedDialogSetNormalizesToEmpty`.

- [x] **Step 2: Run dialog tests and confirm RED**

Run:
`./gradlew :TMessagesProj:testNormalDebugUnitTest --tests 'tw.nekomimi.nekogram.filters.AyuFilterExclusionSnapshotsTest' --no-configuration-cache`

Expected: FAIL because `AyuFilterExclusionSnapshots` does not exist.
- [x] **Step 3: Implement only dialog snapshot behavior**

Use one immutable holder containing exact raw key + unmodifiable copied `Set<Long>`. Loader exceptions propagate and publish nothing. `publishDialogs` copies before publication. `invalidateDialogs` clears only the dialog holder.

- [x] **Step 4: Run focused tests and confirm dialog GREEN**

Run the Step 2 command. Expected: all dialog tests PASS.

- [x] **Step 5: Add failing shared snapshot tests**

Add:
`firstSharedLookupLoadsDeepSnapshotOnce`,
`sameSharedEpochReturnsSameReadOnlyView`,
`sourceMapMutationAfterLoadCannotChangeSnapshot`,
`sharedViewRejectsMutation`,
`sharedCopyIsMutableAndIndependent`,
`invalidateSharedForcesReload`,
`missingDialogReturnsReadOnlyEmptyViewAndMutableCopy`,
`invalidateRacingFirstLoadWinsForNextLookup`.

The race test uses test-only threads plus latches: block the loader, start invalidation, release the loader, wait for invalidation to return, then assert the next lookup invokes a fresh loader.

- [x] **Step 6: Run focused tests and confirm shared RED**

Run the same focused command. Expected: FAIL because shared APIs/semantics are not yet implemented.

- [x] **Step 7: Implement shared snapshots and `invalidateAll`**

Deep-copy the loader map and every nested set before publishing a read-only map/set graph. Serialize shared cache-miss load/publication/invalidation in the helper so invalidation cannot be overwritten by an older in-flight load. Cache-hit `sharedView` returns the published set without a new collection.

- [x] **Step 8: Run focused tests and full suite GREEN**

Run the focused command, then:
`./gradlew :TMessagesProj:testNormalDebugUnitTest --no-configuration-cache`

Expected: focused tests PASS and full suite has zero failures/errors.

- [x] **Step 9: Purity/diff check and commit Task 1**

Verify helper imports only `java.*`, contains no Android/Telegram/Room/NaConfig/Gson references, and creates no thread/executor. Run `git diff --check`.

Commit: `perf: add ayufilter exclusion snapshots`

---
### Task 2: Integrate raw-config dialog snapshots

**Files:**
- Modify: `TMessagesProj/src/main/java/tw/nekomimi/nekogram/filters/AyuFilter.java:191,331-371,735-767`
- Test: `TMessagesProj/src/test/java/tw/nekomimi/nekogram/filters/AyuFilterDialogSnapshotShapeTest.java`
- Consume: `AyuFilterExclusionSnapshots` from Task 1.

**Interfaces:**
- Add nested lazy holder `ExclusionSnapshotsHolder.INSTANCE`.
- Add private `Set<Long> parseExcludedDialogs(String serialized)`.
- Add private `Set<Long> getExcludedDialogsView()`.
- `isDialogExcluded(long)` reads the view only.
- `setDialogExcluded(long,boolean)` copies the view, persists on change, publishes with the exact persisted raw string, then keeps `AyuFilterCache.clearDialog(dialogId)`.
- `rebuildCache()` calls `ExclusionSnapshotsHolder.INSTANCE.invalidateAll()`.

- [x] **Step 1: Capture parity baselines before editing**

With a brace-aware source extractor capture the P3 bodies of `isFiltered`, `getMessageText`, `isFilterMatch`, and the current `rebuildCache` notification tail. Record `regexFiltersEnabled` default from `NaConfig.kt`.

Expected: `isFiltered` shows `isDialogExcluded` before `AyuFilterCache.get`; default is `false`.

- [x] **Step 2: Write failing dialog integration shape tests**

The test reads `AyuFilter.java` source using a helper that resolves either repo-root or module-root working directories. Assert:
`ExclusionSnapshotsHolder` exists;
`getExcludedDialogsView` reads `getRegexFiltersExcludedDialogs().String()` and delegates through `.dialogs(`;
the parse fallback retains `FileLog.e("AyuFilter.getExcludedDialogs", e)`;
`setDialogExcluded` contains `.publishDialogs(`;
`setConfigString` occurs before `publishDialogs`, which occurs before `AyuFilterCache.clearDialog`;
`rebuildCache` contains `.invalidateAll()`;
`clearAllFilters` still calls `rebuildCache()` after its existing config writes.

- [x] **Step 3: Run shape test and confirm RED**

Run:
`./gradlew :TMessagesProj:testNormalDebugUnitTest --tests 'tw.nekomimi.nekogram.filters.AyuFilterDialogSnapshotShapeTest' --no-configuration-cache`

Expected: FAIL because baseline `AyuFilter` has no snapshot wiring.
- [x] **Step 4: Implement minimal dialog integration**

Keep the raw config read, Gson parse, and existing `FileLog.e("AyuFilter.getExcludedDialogs", e)` fallback inside the same catch boundary. `getExcludedDialogsView()` obtains the current raw string and calls helper `dialogs`; parse exceptions return an empty read-only set for that call and are not published.

`setDialogExcluded` starts from `new HashSet<>(getExcludedDialogsView())`. On membership change, serialize with existing Gson behavior, write config, publish using that exact serialized string, then clear the dialog LRU exactly once.

Do not edit `isFiltered`, regex matching, Room loaders, or public shared-exclusion methods in this task.

- [x] **Step 5: Run focused dialog/helper tests and Java compile**

Run Task 1 helper test plus Task 2 shape test, then:
`./gradlew :TMessagesProj:compileNormalDebugJavaWithJavac --no-configuration-cache`

Expected: PASS.

- [x] **Step 6: Re-run parity assertions**

Brace-aware compare `isFiltered`, `getMessageText`, and `isFilterMatch` against P3: exact body match after newline normalization. Verify `regexFiltersEnabled` is still `false`. Verify `isFiltered` ordering remains exclusion before LRU. Verify the captured `rebuildCache` notification tail remains unchanged apart from the new invalidation call earlier in the method.

- [x] **Step 7: Run full suite and commit Task 2**

Run:
`./gradlew :TMessagesProj:testNormalDebugUnitTest --no-configuration-cache`
then `git diff --check`.

Expected: zero failures/errors.

Commit: `perf: cache ayufilter excluded dialogs`

---

### Task 3: Integrate no-copy shared-exclusion views

**Files:**
- Modify: `TMessagesProj/src/main/java/tw/nekomimi/nekogram/filters/AyuFilter.java:220-305,770-913`
- Test: `TMessagesProj/src/test/java/tw/nekomimi/nekogram/filters/AyuFilterSharedSnapshotShapeTest.java`
- Consume: Task 1 shared snapshot APIs and Task 2 lazy holder.

**Interfaces:**
- Add private `Map<Long, ? extends Set<String>> loadExcludedSharedFilterIdsMap()` using existing `getExcludedFilterEntries()` + `buildExcludedSharedFilterIdsMap(...)`.
- Add private `Set<String> getExcludedSharedFilterIdsView(long dialogId)`.
- Public `HashSet<String> getExcludedSharedFilterIds(long)` delegates to helper `sharedCopy`.
- Internal `isFilteredInternal`, `findFilteredRanges`, and `isSharedFilterExcluded` use the no-copy view.
- [x] **Step 1: Capture cold-path/final ownership baselines**

Capture exact P3 bodies of `loadSharedFilters`, `loadChatFilterEntries`, and `getExcludedFilterEntries`. Record SHA-256 of `AyuFilterCache.java`. Record all four baseline assignments `excludedSharedFilterIdsByDialog = null`.

- [x] **Step 2: Write failing shared integration shape tests**

Assert baseline must change to satisfy all:
the old volatile `excludedSharedFilterIdsByDialog` field is absent;
`getExcludedSharedFilterIdsView` exists;
`isFilteredInternal`, `findFilteredRanges`, and `isSharedFilterExcluded` use the view instead of public copying getter;
public `getExcludedSharedFilterIds` contains `.sharedCopy(`;
the three non-rebuild invalidation sites call `.invalidateShared()`.

- [x] **Step 3: Run shared shape test and confirm RED**

Run:
`./gradlew :TMessagesProj:testNormalDebugUnitTest --tests 'tw.nekomimi.nekogram.filters.AyuFilterSharedSnapshotShapeTest' --no-configuration-cache`

Expected: FAIL against the old volatile-map/copying integration.

- [x] **Step 4: Implement shared snapshot integration**

Remove direct shared-map ownership from `AyuFilter`. The loader still performs the same synchronous Room call through `getExcludedFilterEntries()` and the same map-building logic; helper deep-copies it at publication.

Use borrowed read-only views only for internal membership checks. Keep public copy mutable/independent. Replace invalidation assignments in add/remove/filter-removal with `invalidateShared()`; `rebuildCache()` already uses `invalidateAll()`. Preserve existing `AyuFilterCache.clearAll()` calls and ordering.

- [x] **Step 5: Run focused tests + compile GREEN**

Run Task 1 helper test, Task 2 dialog shape test, Task 3 shared shape test, then Java compile.

Expected: PASS.

- [x] **Step 6: Re-run cold-path parity gates**

Brace-aware compare the three frozen Room-loader bodies to P3 and compare `AyuFilterCache.java` SHA-256. Expected: exact match. Search for any remaining `excludedSharedFilterIdsByDialog = null`; expected: none.

- [x] **Step 7: Full suite, diff check, commit Task 3**

Run full `:TMessagesProj:testNormalDebugUnitTest`, `git diff --check`, and inspect changed files.

Commit: `perf: avoid ayufilter shared exclusion copies`

---
### Task 4: Whole-P4A regression and invariant gate

**Files:**
- No planned product-code changes. Any defect exposed here returns to the owning task's RED→GREEN cycle.

- [x] **Step 1: Run all new P4A tests fresh at HEAD**

Run the helper test and both integration-shape tests in one Gradle invocation.

Expected: PASS.

- [x] **Step 2: Run full unit suite fresh**

Run:
`./gradlew :TMessagesProj:testNormalDebugUnitTest --no-configuration-cache`

Expected: `BUILD SUCCESSFUL`, zero failures/errors.

- [x] **Step 3: Re-run frozen behavior checks**

Require exact P3 body match for `isFiltered`, `getMessageText`, `isFilterMatch`, `loadSharedFilters`, `loadChatFilterEntries`, and `getExcludedFilterEntries`. Require unchanged `AyuFilterCache.java` SHA-256 and `RegexFilters=false`.

- [x] **Step 4: Audit complete P4A runtime diff from P3**

Allowed runtime files are only `AyuFilterExclusionSnapshots.java` and the exclusion ownership/invalidation/membership portions of `AyuFilter.java`. Test and approved docs are allowed. Reject UI-cell, MessageObject, NotificationsController, DAO/schema, config-definition, or AyuFilterCache runtime edits.

- [x] **Step 5: Static hot-path assertions**

Require:
unchanged raw config can reuse the same dialog snapshot;
parse failure is not published;
dialog raw-key mismatch reloads;
`isDialogExcluded` remains before LRU lookup;
internal shared membership paths do not call the public copy getter;
public shared getter still returns `HashSet<String>`;
all shared invalidation points remain;
`clearAllFilters()` still reaches `rebuildCache()` after clearing serialized filter data;
no new persistent/asynchronous work exists.

- [x] **Step 6: Final hygiene and remote verification**

Run `git diff --check`, `git status --short`, inspect `git log --oneline --decorate -5`, push completed task commits, and compare local HEAD to `origin/<implementation-branch>`.

Expected: clean tree and identical local/remote SHA.

- [x] **Step 7: Review P4A as one slice**

Review raw-config external-change visibility, malformed-config retry, setter publish ordering, deep-copy ownership, invalidation/load race, defensive public API, and strict P4B boundary. Do not merge `dev`, release, install, or start device profiling.

## Execution Gate

Execution mode remains **Native** from the approved workflow. After this plan is approved, use `superpowers:executing-plans` and implement Tasks 1→4 sequentially with RED→minimal change→GREEN→full verification→commit boundaries.

P4B begins only after P4A is complete and reviewed.
