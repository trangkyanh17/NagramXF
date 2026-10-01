# Ayu DB/UI I/O Isolation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the three confirmed Ayu database/filesystem reads from UI-critical paths without changing their final visible behavior.

**Architecture:** Keep `30dcd6ce` as the behavioral baseline. Reuse `Utilities.globalQueue` for revision/history work and `AyuQueues.spyQueue` for read-date work; add only small pure-Java coordination/value seams for deterministic JUnit tests. Every async result is keyed and generation-guarded before it may touch UI state.

**Tech Stack:** Java 21, Android/Telegram UI, Room 2.8.4, existing `DispatchQueue`/`Utilities.globalQueue`, JUnit 4.13.2.

**Spec:** `docs/superpowers/specs/2026-10-01-ayu-db-ui-io-design.md`

## Global Constraints

- Golden baseline is `30dcd6ce7b5b0279fa86f302ca61cd272bfa3068`.
- Do not remove or modify Room `.allowMainThreadQueries()` in P1.
- No schema, entity, migration, stored-data format, Ghost behavior, or feature-default changes.
- No new `Thread`, `ExecutorService`, scheduler, polling loop, or always-live worker.
- Enqueue background work before first-use `AyuMessagesController.getInstance()` or attachment filesystem access.
- Preserve revision ordering, history-menu eligibility, and read-date precedence.
- Errors log and degrade to no-data for that request; they are never cached as permanent truth.
- Real-device battery/thermal/jank claims are deferred to the later device-validation gate.

## Review Focus

- Same message, newer request finishes first: the older callback must be rejected. Covered by Task 1 gate tests and Task 2 history reload tests.
- Same account/dialog/message but a recycled/different target view: the old menu callback must be rejected. Covered by Task 1 target-identity test and Task 3 menu gate use.
- Same dialog/message across different accounts: callbacks must not cross accounts. Covered by Task 1 request-key tests and used by Tasks 2/4.
- Attachment directory missing or `File.list()` returning `null`: revisions must still load in order and media lookup must remain safe. Covered by Task 2 snapshot tests.
- A Room/runtime lookup fails once and succeeds on retry: first request resolves safely, later request is not poisoned. Covered by Task 1 safe-lookup test and reused by Tasks 2–4.

## File Structure

- Create `TMessagesProj/src/main/java/com/radolyn/ayugram/utils/AyuAsyncRequestGate.java`: generation/key guard for late async results.
- Create `TMessagesProj/src/main/java/com/radolyn/ayugram/utils/AyuUiRequestKey.java`: account/dialog/message plus optional target identity.
- Create `TMessagesProj/src/main/java/com/radolyn/ayugram/utils/AyuSafeLookup.java`: stateless runtime-exception fallback/log seam.
- Create `TMessagesProj/src/main/java/com/radolyn/ayugram/messages/AyuHistorySnapshot.java`: immutable revision + attachment-name snapshot.
- Create `TMessagesProj/src/main/java/com/radolyn/ayugram/utils/AyuRevisionPresenceResolver.java`: pure cheap eligibility + query decision.
- Create `TMessagesProj/src/main/java/com/radolyn/ayugram/controllers/AyuReadDateResolver.java`: pure read-date precedence/disabled-query rule.
- Modify only the three confirmed UI paths plus their owning controllers: `AyuMessageHistory`, `ChatActivity`, `MessageDetailsActivity`, `AyuMessagesController`, `AyuSpyController`.
- Add focused JUnit tests under matching `TMessagesProj/src/test/java/...` packages.

---

### Task 1: Pure-Java async coordination primitives

**Files:**
- Create: `TMessagesProj/src/main/java/com/radolyn/ayugram/utils/AyuAsyncRequestGate.java`
- Create: `TMessagesProj/src/main/java/com/radolyn/ayugram/utils/AyuUiRequestKey.java`
- Create: `TMessagesProj/src/main/java/com/radolyn/ayugram/utils/AyuSafeLookup.java`
- Test: `TMessagesProj/src/test/java/com/radolyn/ayugram/utils/AyuAsyncUiPrimitivesTest.java`

**Interfaces:**
- Produces: `AyuAsyncRequestGate<K>.begin(K) -> long`, `isCurrent(long,K) -> boolean`, `invalidate() -> void`.
- Produces: `AyuUiRequestKey.forMessage(int,long,int)` and `forTarget(int,long,int,Object)`; equality includes account/dialog/message and target **identity** (`==`), not target `.equals()`.
- Produces: `AyuSafeLookup.run(Supplier<T>, T fallback, Consumer<Throwable>) -> T`; catch `RuntimeException`, call the error consumer once, return fallback, keep no state.

- [ ] **Step 1: Write failing key/gate tests**

Tests: `latestRequestWinsForSameKey`, `differentAccountIsDifferentKey`, `differentMessageIsDifferentKey`, `differentTargetIdentityIsDifferentKey`, `invalidateRejectsPendingRequest`.

- [ ] **Step 2: Run focused test and confirm RED**

Run with JDK 21/canonical SDK:
`./gradlew :TMessagesProj:testNormalDebugUnitTest --tests 'com.radolyn.ayugram.utils.AyuAsyncUiPrimitivesTest' --no-configuration-cache`
Expected: FAIL because the new types do not exist.

- [ ] **Step 3: Implement the three primitives**

`AyuAsyncRequestGate` stores only the newest generation/key. `begin` increments generation and replaces the key; `invalidate` increments generation and clears the key. `isCurrent` returns true only for the newest matching key.

`AyuUiRequestKey` fields/signatures:
`int account`, `long dialogId`, `int messageId`, nullable `Object targetIdentity`; static factories as defined above.

`AyuSafeLookup.run(...)` must be stateless so an exception cannot poison later retries.

- [ ] **Step 4: Add safe-lookup retry test and run GREEN**

Test `failedLookupReturnsFallbackAndNextCallCanSucceed`: first supplier throws `IllegalStateException`, logger count becomes 1, result is fallback; second invocation returns the real value.

Run the focused test command from Step 2. Expected: PASS.

- [ ] **Step 5: Diff-check and commit Task 1**

Run: `git diff --check` and inspect that Task 1 has no Android/UI behavior change.

Commit: `perf: add async UI request guards`

---

### Task 2: Move revision history + attachment snapshot off the UI thread

**Files:**
- Create: `TMessagesProj/src/main/java/com/radolyn/ayugram/messages/AyuHistorySnapshot.java`
- Modify: `TMessagesProj/src/main/java/com/radolyn/ayugram/messages/AyuMessagesController.java:782-788`
- Modify: `TMessagesProj/src/main/java/com/radolyn/ayugram/ui/AyuMessageHistory.java:92-126,129-257,275-286`
- Test: `TMessagesProj/src/test/java/com/radolyn/ayugram/messages/AyuHistorySnapshotTest.java`

**Interfaces:**
- Produces: immutable `AyuHistorySnapshot.of(List<EditedMessage>, String[])` and `empty()`, plus `getRevisions() -> List<EditedMessage>` and `getAttachmentFileNames() -> String[]`; preserve revision order, expose an unmodifiable revision list, return a cloned filename array, normalize null revisions to empty.
- Produces in controller: `public static AyuHistorySnapshot loadHistorySnapshot(long userId, long dialogId, int messageId)`; synchronous worker-only method, first calls `getInstance()` **inside the worker**, then reads revisions and attachment filename snapshot.
- Consumes: `AyuAsyncRequestGate<AyuUiRequestKey>` and `AyuSafeLookup` from Task 1.

- [ ] **Step 1: Write failing snapshot tests**

Tests: `snapshotPreservesRevisionOrder`, `snapshotDefensivelyCopiesAttachmentNames`, `nullAttachmentListingDoesNotDiscardRevisions`, `emptyNormalizesNullRevisionList`.

- [ ] **Step 2: Run snapshot test and confirm RED**

Run: `./gradlew :TMessagesProj:testNormalDebugUnitTest --tests 'com.radolyn.ayugram.messages.AyuHistorySnapshotTest' --no-configuration-cache`
Expected: FAIL because `AyuHistorySnapshot` does not exist.

- [ ] **Step 3: Implement `AyuHistorySnapshot` and controller loader**

`loadHistorySnapshot(...)` must not create threads. Query revisions through the existing controller; snapshot `AyuMessagesController.attachmentsPath` using `exists()`/`list()` on the same worker. A missing directory or null list yields `attachmentFileNames=null` without dropping successful revisions.

- [ ] **Step 4: Refactor `AyuMessageHistory` to async reload**

Remove constructor `updateHistory()` call and direct `cacheAttachmentFileNames()` filesystem access. Add `requestHistoryReload()` that captures `getCurrentAccount()`, dialog, and message key, starts a new generation, then posts to `Utilities.globalQueue` before any controller/filesystem touch. Start the initial request once at the end of `createView()` after `listView` and `emptyView` exist.

The worker wraps `AyuMessagesController.loadHistorySnapshot(...)` in `AyuSafeLookup` with `AyuHistorySnapshot.empty()` fallback and `error -> FileLog.e(error)`, then posts the result to `AndroidUtilities.runOnUIThread`.

On UI delivery, apply only if `historyRequestGate.isCurrent(generation,key)` and the fragment is still alive. Set `messages`, `cachedAttachmentFileNames`, `rowCount`, rebuild message objects, refresh adapter if present, then update empty state.

Maintain `historyLoaded=false` while pending. Before first completion, `updateEmptyView()` must keep the normal `NoMessages` empty state hidden rather than flashing a false empty result.

`MESSAGE_EDITED_NOTIFICATION` calls `requestHistoryReload()` only for the matching dialog/message **and only after `fragmentView` exists**; otherwise the initial `createView()` request owns first load. Remove its immediate `notifyDataSetChanged()` because the accepted callback owns the refresh. Starting a newer reload automatically makes the older callback stale. `onFragmentDestroy()` calls `historyRequestGate.invalidate()` before clearing UI references.

- [ ] **Step 5: Add generation/failure regression tests using Task 1 gate**

Extend focused tests to cover: first history token rejected after a second begins; invalidated token rejected after destroy; failed safe lookup returns empty but a later generation can succeed.

- [ ] **Step 6: Run focused tests + compile path GREEN**

Run both Task 1 and Task 2 test classes. Then run:
`./gradlew :TMessagesProj:compileNormalDebugJavaWithJavac --no-configuration-cache`
Expected: PASS.

- [ ] **Step 7: Static check UI path and commit Task 2**

Verify `AyuMessageHistory` no longer directly calls `getRevisions(...)` or `File.list()`/`attachmentsDir.list()` from constructor/UI update code. Run `git diff --check`.

Commit: `perf: move ayu history loading off UI thread`

---

### Task 3: Defer ChatActivity history-presence lookup without replaying menu side effects

**Files:**
- Create: `TMessagesProj/src/main/java/com/radolyn/ayugram/utils/AyuRevisionPresenceResolver.java`
- Modify: `TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java:32720-33049,3728+`
- Test: `TMessagesProj/src/test/java/com/radolyn/ayugram/utils/AyuRevisionPresenceResolverTest.java`

**Interfaces:**
- `AyuRevisionPresenceResolver.isEligible(boolean featureEnabled, boolean hasFromPeer, long fromUserId, long selfUserId, boolean expiredVoiceOrRound) -> boolean` reproduces the baseline cheap preconditions exactly.
- `AyuRevisionPresenceResolver.resolve(boolean eligible, BooleanSupplier query) -> boolean`; when `eligible=false`, do not invoke `query`; otherwise return its boolean.
- `ChatActivity` owns `AyuAsyncRequestGate<AyuUiRequestKey> ayuMenuRequestGate`.
- Add private `AyuMenuPreflight prepareAyuMenuPreflight(View v, boolean single, boolean longpress)`; result fields are `MessageObject message`, `MessageObject primaryMessage`, `int type`, `boolean effectiveSingle`, `boolean terminal`, `boolean terminalResult`. It owns the existing prefix through sponsored/thread `single` promotion and performs each existing prefix side effect at most once.
- Add private overload `createMenu(View v, boolean single, boolean listView, float x, float y, boolean searchGroup, boolean longpress, boolean suggestEdit, boolean onDoubleTapped, AyuMenuPreflight preflight, Boolean ayuHasRevisions)`; the existing deepest overload delegates to it with `null, null`.

- [ ] **Step 1: Write failing eligibility/query tests**

Tests: disabled feature/no-from/self-message/expired voice-or-round each return ineligible and invoke query zero times; eligible source returning true/false returns the same value.

- [ ] **Step 2: Run focused test and confirm RED**

Run: `./gradlew :TMessagesProj:testNormalDebugUnitTest --tests 'com.radolyn.ayugram.utils.AyuRevisionPresenceResolverTest' --no-configuration-cache`
Expected: FAIL because the resolver does not exist.

- [ ] **Step 3: Implement resolver and make test GREEN**

Keep it pure Java; no Android imports, caching, thread ownership, or side effects.

- [ ] **Step 4: Add a resumable preflight seam before menu-model construction**

Keep every existing `createMenu(...)` call site unchanged. In the deepest overload, factor the existing prefix through the sponsored/thread `single` promotion (current lines 32725-32847) into a private `AyuMenuPreflight` result: `message`, `primaryMessage`, `type`, and `effectiveSingle`, plus an early terminal result when baseline would already return. This prefix owns the existing one-time side effects (`hideHints`, `factCheckHint.hide`, action handlers) so they cannot replay after an async lookup.

After successful preflight, evaluate `preflight.effectiveSingle || preflight.type < MESSAGE_TYPE_MEDIA || preflight.type == MESSAGE_TYPE_SEND_ERROR_TEXT` without mutating menu state. Only requests that will actually reach the menu-model branch and satisfy `AyuRevisionPresenceResolver.isEligible(...)` are deferred. Ineligible/non-menu requests continue synchronously with `ayuHasRevisions=false` and issue no revision query.

For a deferred request, create `AyuUiRequestKey.forTarget(currentAccount, message.getDialogId(), message.getId(), v)`, begin a generation, post to `Utilities.globalQueue`, and only there call `AyuMessagesController.getInstance().hasAnyRevisions(...)` through `AyuSafeLookup` with `false` fallback. Return `true` to consume the initiating gesture while pending.

The callback re-enters a private continuation that starts **after preflight but before selected-state resets/menu-array construction**, passing the saved preflight result and resolved `ayuHasRevisions`. Therefore the early action prefix and the later menu-building body each execute at most once.

Accept the callback only when generation/key is current, fragment is live, the target `View` still exposes the same dialog/message identity, and no newer menu request superseded it. At the original Ayu insertion point, replace only the synchronous `hasAnyRevisions(...)` term with the resolved boolean; keep item position and all other conditions unchanged.

- [ ] **Step 5: Add stale-target regression test at the pure gate/key seam**

Use two distinct target identity objects for the same account/dialog/message. The second key/generation must reject the first callback.

- [ ] **Step 6: Invalidate pending menu query on fragment destruction**

Add `ayuMenuRequestGate.invalidate()` to `ChatActivity.onFragmentDestroy()`; do not alter unrelated cleanup.

- [ ] **Step 7: Run resolver/gate tests + compile GREEN**

Run Task 1 + Task 3 focused tests, then `:TMessagesProj:compileNormalDebugJavaWithJavac` with JDK 21/canonical SDK.

- [ ] **Step 8: Review one-call behavior and commit Task 3**

Inspect the diff to prove the original menu body is reached exactly once per completed request and no synchronous `hasAnyRevisions(...)` remains in the resolved UI body. Run `git diff --check`.

Commit: `perf: move edit-history menu lookup off UI thread`

---

### Task 4: Resolve message read-date off the UI thread

**Files:**
- Create: `TMessagesProj/src/main/java/com/radolyn/ayugram/controllers/AyuReadDateResolver.java`
- Modify: `TMessagesProj/src/main/java/com/radolyn/ayugram/controllers/AyuSpyController.java:89-103`
- Modify: `TMessagesProj/src/main/java/tw/nekomimi/nekogram/ui/MessageDetailsActivity.java:352-360,514-538,599-604,709-724`
- Test: `TMessagesProj/src/test/java/com/radolyn/ayugram/controllers/AyuReadDateResolverTest.java`

**Interfaces:**
- `AyuReadDateResolver.resolve(boolean enabled, Supplier<SpyMessageRead> readLookup, Supplier<SpyMessageContentsRead> contentsLookup) -> int`.
- Disabled returns `0` without invoking either supplier. Enabled prefers a non-null `SpyMessageRead.entityCreateDate`; only when absent does it query `SpyMessageContentsRead`; no row returns `0`.
- `AyuSpyController.getReadDateTimestamp(int account, long dialogId, int messageId) -> int` owns DAO access and wraps resolver execution in `AyuSafeLookup` with `0` fallback and `error -> FileLog.e(error)`.

- [ ] **Step 1: Write failing resolver tests**

Tests: `messageReadWinsWithoutContentsQuery`, `contentsReadIsFallback`, `noRowsReturnsZero`, `disabledDoesNotQuery`, `lookupFailureReturnsZeroAndNextCallCanRetry` (the last composes resolver with `AyuSafeLookup`).

- [ ] **Step 2: Run focused test and confirm RED**

Run: `./gradlew :TMessagesProj:testNormalDebugUnitTest --tests 'com.radolyn.ayugram.controllers.AyuReadDateResolverTest' --no-configuration-cache`
Expected: FAIL because resolver/controller API does not exist.

- [ ] **Step 3: Implement resolver + controller API**

Do not cache `SpyDao`; each enabled lookup obtains it through existing `AyuData.getSpyDao()`/LockedDao path. Resolve `selfUserId` for the supplied account and preserve account/dialog/message parameters.

- [ ] **Step 4: Add async read-date state to `MessageDetailsActivity`**

Fields: `AyuAsyncRequestGate<AyuUiRequestKey> readDateRequestGate`, `int readDateTimestamp`, `boolean readDateResolved`.

After `updateRows()` in `onFragmentCreate()`, start a read-date request only when `readDateRow >= 0`. Begin a key for current account/dialog/message, post to `AyuQueues.spyQueue`, then call `AyuSpyController.getReadDateTimestamp(...)` on that queue.

Deliver to UI thread; accept only a current generation/key and a live fragment. Store timestamp, mark resolved, and call `listAdapter.notifyItemChanged(readDateRow)` only when adapter exists and the row is still valid.

The adapter's `readDateRow` branch performs **no DAO call**: before resolution show `getString(R.string.Loading)`; after resolution format positive timestamp exactly as baseline or show `AyuReadDateUnknown` for `0`.

`onFragmentDestroy()` invalidates `readDateRequestGate` before observer cleanup.

- [ ] **Step 5: Run focused resolver/gate tests + compile GREEN**

Run Task 1 + Task 4 focused tests and `:TMessagesProj:compileNormalDebugJavaWithJavac --no-configuration-cache`.

- [ ] **Step 6: Static UI-path check and commit Task 4**

Verify the `readDateRow` bind branch contains no `AyuData`, `SpyDao`, `getMessageRead`, or `getMessageContentsRead` call. Run `git diff --check`.

Commit: `perf: move read-date lookup off UI thread`

---

### Task 5: P1 whole-slice regression and invariant verification

**Files:**
- No planned product-code changes; only fix defects exposed by this gate, then rerun the owning task's RED/GREEN cycle before committing that fix.

- [ ] **Step 1: Run every new focused test together**

Run the four new test classes (`AyuAsyncUiPrimitivesTest`, `AyuHistorySnapshotTest`, `AyuRevisionPresenceResolverTest`, `AyuReadDateResolverTest`) in one Gradle invocation.

- [ ] **Step 2: Run the complete baseline unit suite**

Environment: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`, `ANDROID_HOME=/srv/nagramxf-sdk/android-sdk`, `ANDROID_SDK_ROOT=/srv/nagramxf-sdk/android-sdk`.

Run: `./gradlew :TMessagesProj:testNormalDebugUnitTest --no-configuration-cache`
Expected: `BUILD SUCCESSFUL`, zero test failures/errors.

- [ ] **Step 3: Verify forbidden resource/behavior changes are absent**

Inspect `git diff 30dcd6ce...HEAD` and search added lines for `new Thread`, `Executors.new`, `scheduleAtFixedRate`, `scheduleWithFixedDelay`, schema/version/migration edits, feature-default edits, and changes to `.allowMainThreadQueries()`.
Expected: none introduced by P1.

- [ ] **Step 4: Verify the three original UI-thread reads are gone**

Confirm:
1. `AyuMessageHistory` constructor/UI update path has no direct revision query or attachment directory listing.
2. `ChatActivity` resolved menu body has no synchronous `hasAnyRevisions(...)` query; that call exists only inside `Utilities.globalQueue` work.
3. `MessageDetailsActivity` row binding has no spy DAO/read-date query; lookup exists only on `AyuQueues.spyQueue`.

- [ ] **Step 5: Fresh diff/build hygiene**

Run `git diff --check`, `git status --short`, and a fresh `:TMessagesProj:compileNormalDebugJavaWithJavac --no-configuration-cache` if the full unit run did not already compile the final diff.

- [ ] **Step 6: Review commits as one P1 slice**

Review from `30dcd6ce` through Task 4 commits for behavioral parity, lifecycle safety, error retryability, and absence of unrelated refactors. Do not merge/release/deploy or start device battery/thermal testing in this task.

## Execution Gate

Execution mode is already **Native**. After this plan is approved, use `superpowers:executing-plans`; implement Tasks 1→5 sequentially, keeping each RED→minimal change→GREEN→broader verification→commit boundary. Product/device validation remains a later gate after the optimization program has progressed further.
