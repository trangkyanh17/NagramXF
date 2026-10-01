# DummyMessageWaiter Event Synchronization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `DummyMessageWaiter` 25 ms polling and its five-minute watcher thread with bounded event-driven synchronization while preserving current Ayu Forward sequencing behavior.

**Architecture:** Model the existing waiter semantics in a pure-Java state machine, then drive it from existing Telegram `NotificationCenter` events and bounded queue snapshots. Use a latch for the 3.5-second identification window; keep `SyncWaiter` as the owner of the existing 300-second completion timeout and subscription release.

**Tech Stack:** Java 21, Telegram `NotificationCenter`, existing `SendMessagesHelper`/`SyncWaiter`, `CountDownLatch`, JUnit 4.13.2.

**Spec:** `docs/superpowers/specs/2026-10-01-dummy-message-waiter-event-sync-design.md`

## Global Constraints

- Behavioral baseline is `30dcd6ce7b5b0279fa86f302ca61cd272bfa3068`; cumulative implementation base is `6a87d4b7c2e68c9ae52693ee048b8af6565ce70c`.
- Preserve `LOOKUP_TIMEOUT_MS = 3500L` and `SyncWaiter` overall timeout of 300 seconds.
- Preserve Ayu Forward send/upload ordering and the current `dispatchSendSync(...)` public signature/return behavior.
- Preserve loose pre-identification `alreadySent` semantics; do not tighten unrelated ACK/server/error behavior in this optimization.
- Do not add a `Thread`, executor, scheduler, timer, recurring queue scan, or polling loop.
- Do not change Telegram send restrictions, feature defaults, Room/schema, CI, signing, or unrelated waiter behavior.

## Review Focus

- Ambiguous ACK/error before target identification must not end the 3.5-second lookup window early; Task 1 pins this with an early-terminal/expiry test.
- Scheduled/ephemeral sends can be event-silent for `sendingMessagesChanged`; Task 1 pins matching-server direct identification and baseline-ID rejection.
- A target candidate may still be present while another baseline pending item disappears, making queue size `<= baselinePendingCount`; Task 1 pins the baseline queue-drain release rule exactly.
- The final reconciliation snapshot can fail; Task 1 pins that `expireLookupWindow(null)` never reuses stale queue-count evidence to release.
- Dispatch can throw on the UI-thread turn; Task 3 pins that completion reconciliation still runs exactly once and the original runtime exception is rethrown unchanged.

## File Structure

- Create `TMessagesProj/src/main/java/com/radolyn/ayugram/utils/seq/MessageWaitState.java`: pure state machine for baseline IDs, candidate selection, early terminal IDs, failure, lookup expiry, and logical completion.
- Create `TMessagesProj/src/main/java/com/radolyn/ayugram/utils/seq/MessageIdentificationWait.java`: pure bounded-latch helper for signalled/timeout/interrupted identification outcomes.
- Create `TMessagesProj/src/main/java/com/radolyn/ayugram/utils/seq/DispatchCompletionRunner.java`: tiny try/finally seam that guarantees post-dispatch reconciliation without changing exception behavior.
- Create `TMessagesProj/src/main/java/com/radolyn/ayugram/utils/seq/SendWaitSequence.java`: pure ordering seam for selected-ID handoff, upload wait, then message wait.
- Modify `TMessagesProj/src/main/java/com/radolyn/ayugram/utils/seq/DummyMessageWaiter.java`: remove polling/watcher and translate Telegram events into the state machine.
- Modify `TMessagesProj/src/main/java/com/radolyn/ayugram/utils/seq/AyuSequentialUtils.java`: prepare before subscribe, run dispatch with completion hook, await ID without polling, preserve upload/message wait order.
- Add focused JUnit tests in `TMessagesProj/src/test/java/com/radolyn/ayugram/utils/seq/`.

---
### Task 1: Pure message-wait state machine

**Files:**
- Create: `TMessagesProj/src/main/java/com/radolyn/ayugram/utils/seq/MessageWaitState.java`
- Test: `TMessagesProj/src/test/java/com/radolyn/ayugram/utils/seq/MessageWaitStateTest.java`

**Interfaces:**
- Produces package-private `final class MessageWaitState`.
- Constructor: `MessageWaitState(Collection<Integer> baselineIds)`; null entries are ignored and baseline count is the number of unique retained IDs.
- Methods: `void observeSnapshot(List<Integer> currentIds)`, `void observeTerminal(int messageId, boolean failure)`, `void observeServer(int messageId, boolean matchingDialog)`, `void observeDeletion(List<Integer> deletedIds, boolean matchingDialog)`, `void expireLookupWindow(List<Integer> finalSnapshot)`.
- Getters: `int getSendingId()`, `boolean hasFailed()`, `boolean isComplete()`.
- `finalSnapshot == null` means the final queue read failed; mark lookup expired but do not evaluate stale queue-count evidence.

- [ ] **Step 1: Write failing baseline/candidate tests**

Tests: `baselineIdsAreNeverSelected`, `firstNewPendingIdIsSelected`, `dispatchCompletionSnapshotSelectsNewCandidate`, `laterQueueChangeSnapshotSelectsCandidate`, `snapshotOrderDeterminesFirstCandidate`, `candidateAlreadySeenAsTerminalCompletesImmediately`.

- [ ] **Step 2: Run focused test and confirm RED**

Run: `./gradlew :TMessagesProj:testNormalDebugUnitTest --tests 'com.radolyn.ayugram.utils.seq.MessageWaitStateTest' --no-configuration-cache`
Expected: FAIL because `MessageWaitState` does not exist.
- [ ] **Step 3: Implement baseline/candidate state only**

Retain baseline IDs in insertion-agnostic set form. `observeSnapshot(...)` selects the first list entry not present in baseline, never changes an already-selected ID, and marks that a new pending item has been observed.

When a selected candidate already exists in the retained pre-identification terminal-ID set, selection also marks the state complete.

- [ ] **Step 4: Add failing terminal/failure tests**

Tests: `matchingAckAfterIdentificationCompletes`, `unrelatedAckAfterIdentificationDoesNotComplete`, `sendErrorAlwaysMarksFailure`, `matchingSendErrorCompletes`, `unrelatedSendErrorAfterIdentificationMarksFailedWithoutCompleting`, `earlyTerminalIsRetainedWithoutCompletingBeforeExpiry`.

- [ ] **Step 5: Implement terminal semantics and run GREEN**

`observeTerminal(...)` always marks failure when requested. Before identification it only retains the ID; after identification only the selected ID directly completes.

Run the focused test command from Step 2. Expected: PASS for the tests implemented so far.

- [ ] **Step 6: Add failing server/deletion tests**

Tests: `matchingServerDirectlySelectsNonBaselineId`, `matchingServerCannotSelectBaselineId`, `otherDialogServerIsRetainedButNotSelected`, `otherDialogServerCanContributeToPostExpiryQueueDrainWithoutSelection`, `matchingDeletionBeforeIdentificationIsRetained`, `selectedDeletionCompletes`, `unrelatedDeletionIsIgnored`.
- [ ] **Step 7: Implement server/deletion semantics**

`observeServer(...)` retains the terminal ID when unidentified. A matching-dialog, non-baseline ID may become the candidate directly; because the same event is terminal, that selected candidate completes immediately. An already-selected matching ID completes regardless of dialog flag, matching baseline ID-first terminal handling.

`observeDeletion(...)` is a no-op when `matchingDialog=false`; otherwise it retains deleted IDs before identification and completes only when the selected ID is deleted afterward.

- [ ] **Step 8: Add failing queue-drain/expiry tests**

Tests: `candidateThenQueueReturnsToBaselineCompletes`, `candidateStillPresentButQueueCountAtBaselineCompletes`, `ambiguousTerminalBeforeExpiryDoesNotCompleteAtBaseline`, `expiryEnablesAmbiguousTerminalQueueDrain`, `unrelatedEarlyErrorPreservesFailureAndCanReleaseAfterExpiry`, `unrelatedEarlyAckCanReleaseOnlyAfterExpiry`, `expiryWithoutTerminalDoesNotComplete`, `nullFinalSnapshotDoesNotReuseStaleQueueCount`, `multipleSignalsAreIdempotent`.

- [ ] **Step 9: Implement queue-drain and expiry rules**

Track only the most recent successful snapshot count for normal candidate/drain processing. While lookup is active, ambiguous retained terminal IDs do not count as observed-new-pending. `expireLookupWindow(finalSnapshot)` first consumes the supplied final snapshot when non-null, then marks lookup expired; only that successful final snapshot may be used to enable retained-terminal + queue-at/below-baseline completion.

After expiry, a later successful `observeSnapshot(...)` may apply the same retained-terminal queue-drain rule. Completion is monotonic/idempotent.

- [ ] **Step 10: Run full state-machine test GREEN**

Run the Task 1 focused command. Expected: all Task 1 tests PASS.

- [ ] **Step 11: Diff-check and commit Task 1**

Run `git diff --check`; verify no Android/Telegram import in `MessageWaitState`.

Commit: `perf: model message waiter event state`

---
### Task 2: Event-driven waiter and bounded identification latch

**Files:**
- Create: `TMessagesProj/src/main/java/com/radolyn/ayugram/utils/seq/MessageIdentificationWait.java`
- Modify: `TMessagesProj/src/main/java/com/radolyn/ayugram/utils/seq/DummyMessageWaiter.java`
- Test: `TMessagesProj/src/test/java/com/radolyn/ayugram/utils/seq/MessageIdentificationWaitTest.java`

**Interfaces:**
- Produces package-private `MessageIdentificationWait.Result { SIGNALED, TIMED_OUT, INTERRUPTED }`.
- Produces `static Result await(CountDownLatch signal, long timeoutMs, Runnable onTimeout, Runnable onInterrupted)`.
- `DummyMessageWaiter.prepare(long dialogId, ArrayList<Integer> existingIds)` initializes target dialog/state before subscription; `dialogId == 0` normalizes to the current account's self user ID.
- `DummyMessageWaiter.onDispatchCompleted()` performs one safe queue reconciliation.
- `DummyMessageWaiter.awaitSendingId() -> int` blocks at most 3500 ms and returns the identified local ID or `0`.
- Keep `trySetSendingId(long, ArrayList<Integer>)` only as a temporary compatibility adapter during Task 2; Task 3 removes it after the caller migrates.

- [ ] **Step 1: Write failing identification-wait tests**

Tests: `preSignalledLatchReturnsSignalledWithoutCallbacks`, `zeroTimeoutRunsTimeoutCallbackOnce`, `interruptedWaitRestoresInterruptFlagAndRunsInterruptCallbackOnce`.

- [ ] **Step 2: Run focused wait test and confirm RED**

Run: `./gradlew :TMessagesProj:testNormalDebugUnitTest --tests 'com.radolyn.ayugram.utils.seq.MessageIdentificationWaitTest' --no-configuration-cache`
Expected: FAIL because `MessageIdentificationWait` does not exist.
- [ ] **Step 3: Implement `MessageIdentificationWait` and run GREEN**

Use `CountDownLatch.await(timeoutMs, TimeUnit.MILLISECONDS)`. On timeout call `onTimeout` exactly once and return `TIMED_OUT`. On `InterruptedException`, restore the interrupt flag, call `onInterrupted` exactly once, and return `INTERRUPTED`.

Run the Task 2 focused wait test. Expected: PASS.

- [ ] **Step 4: Refactor `DummyMessageWaiter` fields/subscriptions without changing event semantics**

Add `NotificationCenter.sendingMessagesChanged`. Replace polling/watcher fields with `MessageWaitState state`, one `CountDownLatch identificationSignal`, a private state lock, and target `dialogId`. Keep public/observable `sendingId` synchronized from state for current package compatibility.

Delete `WATCHER_TIMEOUT_MS`, `POLL_INTERVAL_MS`, `startQueueWatcher(...)`, and every `Thread.sleep(...)`/watcher `Thread` from this class.

- [ ] **Step 5: Add state-application and safe queue reconciliation**

A single private mutation helper runs `MessageWaitState` changes under the state lock, detects `sendingId` transition `0 -> nonzero`, then outside the lock counts down `identificationSignal`; if state becomes complete it calls idempotent `unsubscribe()` outside the lock.

A single `reconcileQueue()` calls `SendMessagesHelper.getSendingMessageIds(dialogId)` once, feeds a successful snapshot to state, and swallows/logically ignores lookup exceptions exactly as baseline did.

- [ ] **Step 6: Implement prepare/dispatch-completion/identification APIs**

`prepare(...)` installs baseline state before observer subscription. `onDispatchCompleted()` calls one reconciliation. `awaitSendingId()` calls `MessageIdentificationWait.await(...)`; timeout callback performs one final safe snapshot and passes that snapshot (or null on failure) to `state.expireLookupWindow(...)`; interrupt callback calls `unsubscribe()`.
- [ ] **Step 7: Translate Telegram notifications into state events**

`messageReceivedByAck`/`messageSendError`: parse local ID, record terminal/failure first, then if still unidentified perform one target-dialog reconciliation. Do not let an ambiguous pre-ID event end the active lookup window.

`messageReceivedByServer`: parse local ID and event dialog from args; record via `observeServer(id, dialogMatches)`. If still unidentified afterward, reconcile the target queue once. Direct candidate selection is allowed only for matching-dialog IDs absent from baseline.

`messagesDeleted`: compute the current baseline dialog-match rule and pass both deleted IDs and that boolean into `state.observeDeletion(...)`; when still unidentified after a matching deletion event, reconcile once afterward. `sendingMessagesChanged`: reconcile once.

- [ ] **Step 8: Preserve compatibility adapter and failure reporting**

Implement temporary `trySetSendingId(...)` as `prepare(...) -> onDispatchCompleted() -> awaitSendingId()` so Task 2 compiles before Task 3 rewires `AyuSequentialUtils`. `hasFailed()` returns state failure when prepared, OR existing `isTimedOut()`.

- [ ] **Step 9: Run focused state/wait tests and Java compile**

Run Task 1 + Task 2 test classes together, then:
`./gradlew :TMessagesProj:compileNormalDebugJavaWithJavac --no-configuration-cache`
Expected: PASS.

- [ ] **Step 10: Static no-polling gate**

Verify `DummyMessageWaiter.java` contains none of: `Thread.sleep`, `new Thread`, `POLL_INTERVAL_MS`, `WATCHER_TIMEOUT_MS`, `startQueueWatcher`. Verify `getSendingMessageIds(...)` appears only in bounded setup/reconciliation code, not any loop.

- [ ] **Step 11: Run complete unit suite and commit Task 2**

Run `./gradlew :TMessagesProj:testNormalDebugUnitTest --no-configuration-cache`; require zero failures/errors, then `git diff --check`.

Commit: `perf: make message waiter event driven`

---
### Task 3: Preserve dispatch/upload sequencing with a completion hook

**Files:**
- Create: `TMessagesProj/src/main/java/com/radolyn/ayugram/utils/seq/DispatchCompletionRunner.java`
- Create: `TMessagesProj/src/main/java/com/radolyn/ayugram/utils/seq/SendWaitSequence.java`
- Modify: `TMessagesProj/src/main/java/com/radolyn/ayugram/utils/seq/AyuSequentialUtils.java:76-106`
- Modify: `TMessagesProj/src/main/java/com/radolyn/ayugram/utils/seq/DummyMessageWaiter.java` (remove temporary compatibility adapter only)
- Test: `TMessagesProj/src/test/java/com/radolyn/ayugram/utils/seq/DispatchCompletionRunnerTest.java`
- Test: `TMessagesProj/src/test/java/com/radolyn/ayugram/utils/seq/SendWaitSequenceTest.java`

**Interfaces:**
- Produces package-private `static void DispatchCompletionRunner.run(Runnable dispatch, Runnable completion)`.
- Produces package-private `static void SendWaitSequence.run(int sendingId, IntConsumer uploadIdSink, Runnable uploadAwait, Runnable messageAwait)`; nullable callbacks mean that wait stage is absent.
- Consumes Task 2 APIs: `prepare(...)`, `onDispatchCompleted()`, `awaitSendingId()`.

- [ ] **Step 1: Write failing completion-runner tests**

Tests: `completionRunsExactlyOnceAfterSuccessfulDispatch`, `completionRunsExactlyOnceAndOriginalRuntimeExceptionIsRethrown`.

- [ ] **Step 2: Run focused runner test and confirm RED**

Run: `./gradlew :TMessagesProj:testNormalDebugUnitTest --tests 'com.radolyn.ayugram.utils.seq.DispatchCompletionRunnerTest' --no-configuration-cache`
Expected: FAIL because `DispatchCompletionRunner` does not exist.

- [ ] **Step 3: Implement completion runner and run GREEN**

Use a single `try/finally`: execute `dispatch.run()` in `try`, `completion.run()` in `finally`; do not catch or translate the dispatch exception.
- [ ] **Step 4: Write failing send-wait sequencing tests**

Tests: `selectedIdIsPassedBeforeUploadAwait`, `uploadAwaitCompletesBeforeMessageAwait`, `noUploadStageStillAwaitsMessage`, `noMessageStageDoesNotAssignUploadIdButStillAwaitsUpload`.

- [ ] **Step 5: Run sequencing test and confirm RED**

Run: `./gradlew :TMessagesProj:testNormalDebugUnitTest --tests 'com.radolyn.ayugram.utils.seq.SendWaitSequenceTest' --no-configuration-cache`
Expected: FAIL because `SendWaitSequence` does not exist.

- [ ] **Step 6: Implement `SendWaitSequence` and run GREEN**

`run(...)` calls `uploadIdSink.accept(sendingId)` only when the sink is non-null, then `uploadAwait.run()` when non-null, then `messageAwait.run()` when non-null. No catch/retry/threading behavior is added.

- [ ] **Step 7: Rewire `dispatchSendSync(...)` ordering**

When message waiting is enabled: resolve dialog and capture `existingSendingIds`; call `messageWaiter.prepare(dialogId, existingSendingIds)` **before** `messageWaiter.subscribe()`.

Keep upload-waiter subscription order unchanged. Post the UI send action as a wrapper that calls `DispatchCompletionRunner.run(action::dispatch, messageWaiter::onDispatchCompleted)` when a message waiter exists; otherwise keep the current direct dispatch behavior.

After posting, obtain `sendingId` from `messageWaiter.awaitSendingId()` when present. Call `SendWaitSequence.run(...)` with an upload-ID sink only when both upload and message waiters exist, with upload-await when the upload waiter exists, and message-await when the message waiter exists. This preserves the baseline no-message-waiter behavior where upload `messageId` remains its default `0` rather than being explicitly assigned.

- [ ] **Step 8: Remove obsolete compatibility path**

Delete `DummyMessageWaiter.trySetSendingId(...)` once no call site remains. Do not change `dispatchSendSync(...)` signature or its existing final `return true` behavior.

- [ ] **Step 9: Run all four focused P2 tests + compile**

Run `MessageWaitStateTest`, `MessageIdentificationWaitTest`, `DispatchCompletionRunnerTest`, and `SendWaitSequenceTest` together, then `:TMessagesProj:compileNormalDebugJavaWithJavac --no-configuration-cache`.
Expected: PASS.

- [ ] **Step 10: Static sequencing review**

Inspect final `dispatchSendSync(...)`: baseline capture → prepare → subscribe message/upload → post dispatch → bounded ID wait → selected-ID handoff when applicable → upload await → message await. Confirm no blocking wait executes inside the UI lambda.

- [ ] **Step 11: Full unit suite, diff-check, commit Task 3**

Run the complete normal-debug unit suite; require zero failures/errors. Run `git diff --check`.

Commit: `perf: preserve send sequencing without polling`

---
### Task 4: Whole-P2 regression and invariant verification

**Files:**
- No planned product-code changes; only fix defects exposed by this gate through the owning task's RED→GREEN cycle before continuing.

- [ ] **Step 1: Run every new P2 test together**

Run:
`./gradlew :TMessagesProj:testNormalDebugUnitTest --tests 'com.radolyn.ayugram.utils.seq.MessageWaitStateTest' --tests 'com.radolyn.ayugram.utils.seq.MessageIdentificationWaitTest' --tests 'com.radolyn.ayugram.utils.seq.DispatchCompletionRunnerTest' --tests 'com.radolyn.ayugram.utils.seq.SendWaitSequenceTest' --no-configuration-cache`
Expected: PASS.

- [ ] **Step 2: Run complete normal-debug unit suite fresh**

Environment: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`, `ANDROID_HOME=/srv/nagramxf-sdk/android-sdk`, `ANDROID_SDK_ROOT=/srv/nagramxf-sdk/android-sdk`.

Run: `./gradlew :TMessagesProj:testNormalDebugUnitTest --no-configuration-cache`
Expected: `BUILD SUCCESSFUL`, zero failures/errors.

- [ ] **Step 3: Verify polling/thread removal**

Inspect `git diff 6a87d4b7...HEAD`. In `DummyMessageWaiter`, require no `Thread.sleep`, no `new Thread`, no `POLL_INTERVAL_MS`, no `WATCHER_TIMEOUT_MS`, and no recurring/scheduled queue scan.

Verify `sendingMessagesChanged` is subscribed and `getSendingMessageIds(...)` occurs only in baseline capture or bounded event/final-reconciliation paths.
- [ ] **Step 4: Verify timeout and behavior invariants**

Confirm `LOOKUP_TIMEOUT_MS` remains exactly `3500L`; `SyncWaiter.DEFAULT_TIMEOUT_SECONDS` remains exactly `300L`; `dispatchSendSync(...)` signature and final return behavior are unchanged.

Confirm early unrelated ACK/server/error handling remains retained until identification/expiry as specified; selected-ID terminal handling remains idempotent; scheduled/ephemeral matching-server fallback rejects IDs captured in baseline.

- [ ] **Step 5: Verify scope and diff hygiene**

Run `git diff --check`, `git status --short`, and list all files changed from `6a87d4b7`. Expected product scope: `MessageWaitState`, `MessageIdentificationWait`, `DispatchCompletionRunner`, `SendWaitSequence`, `DummyMessageWaiter`, `AyuSequentialUtils`, and their focused tests only.

No feature-default, schema, Room, CI/signing, Ghost, plugin, push, download-waiter, or upload-waiter implementation changes are allowed.

- [ ] **Step 6: Review P2 commits as one slice**

Review from `6a87d4b7` through Task 3 for race parity, UI-thread non-blocking behavior, interruption, timeout, queue-drain semantics, and absence of polling. Do not merge `dev`, release, or start device battery/thermal testing in P2 implementation.

## Execution Gate

Execution mode remains **Native** from the previously approved workflow. After this plan is approved, use `superpowers:executing-plans`; implement Tasks 1→4 sequentially with RED→minimal change→GREEN→broader verification→commit boundaries.

Real-device validation remains deferred until the broader optimization program reaches its device-validation gate.
