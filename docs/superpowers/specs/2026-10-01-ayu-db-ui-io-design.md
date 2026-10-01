# Ayu DB/UI I/O Isolation Design

**Status:** Proposed for user review
**Program:** NagramXF Performance V2, subproject P1
**Baseline:** `30dcd6ce7b5b0279fa86f302ca61cd272bfa3068`

## Goal

Remove confirmed Ayu Room queries from UI-critical execution paths while preserving the final data, menu capabilities, persistence schema, Ghost behavior, and existing feature switches.

This subproject is intentionally narrower than “remove all main-thread database access everywhere.” It fixes the confirmed interactive UI paths first and creates an async contract that later work can reuse.

## Confirmed baseline problem

`AyuData.buildDatabase(...)` calls Room `.allowMainThreadQueries()` in both database-builder branches.

Known UI-path queries enabled by that setting include:

- `AyuMessageHistory.updateHistory()` synchronously calls `AyuMessagesController.getRevisions(...)`, then `cacheAttachmentFileNames()` performs `File.exists()`/`File.list()` from the same UI update path.
- `ChatActivity.createMenu(...)` synchronously calling `AyuMessagesController.hasAnyRevisions(...)` while building a message context menu.
- `MessageDetailsActivity` synchronously calling `AyuSpyController.getMessageRead(...)` and `getMessageContentsRead(...)` while binding the read-date row.

`NekoAyuSpySettingsActivity.loadStats()` is already dispatched to `Utilities.globalQueue`; it is evidence that the codebase already accepts background DAO reads with UI-thread result delivery.
## Behavioral invariants

The implementation must preserve these externally observable contracts:

- Revision history still shows the same saved revisions in the same order once loading completes.
- The “edit history” context-menu item appears for exactly the same eligible message conditions as baseline when revisions exist.
- A message with no revisions does not gain the history item.
- Read-date details resolve from `SpyMessageRead` first and `SpyMessageContentsRead` second, matching the baseline precedence.
- Feature-disabled paths do not issue unnecessary Ayu queries.
- Database replacement/import/cleanup locking through `LockedDao` and `AyuDataLock` remains intact.
- No Room schema version, entity, migration, or stored-data format changes are introduced.
- No new permanent scheduler, polling loop, or always-live executor is introduced solely for these UI reads.

A short-lived loading state is permitted where an immediate synchronous query previously blocked the UI. The final result must be identical to the baseline result for the same database state.

## Scope

### In scope

1. Async revision-list and attachment-directory snapshot loading for `AyuMessageHistory`.
2. Async revision-presence resolution for the `ChatActivity` message menu.
3. Async read-date resolution for `MessageDetailsActivity`.
4. Lifecycle/staleness protection so a late callback cannot update a destroyed fragment, a recycled row, or a different selected message.
5. Pure-Java unit coverage for the async coordination/state rules added by this work.

### Out of scope

- Removing `.allowMainThreadQueries()` globally in this subproject.
- Regex/filter lazy database loading; that belongs to P4.
- DAO/schema/index redesign.
- Changing save-deleted/save-edits/read-date feature defaults.
- Changing attachment maintenance, LastSeen batching, Ghost scheduling, push, plugins, or transcription.
## Architecture

### 1. Reuse existing background execution

Revision/history UI work is enqueued through the existing `Utilities.globalQueue`; spy/read-date work uses the existing `AyuQueues.spyQueue`. No new executor or worker thread is created for P1.

The enqueue operation must occur before touching `AyuMessagesController.getInstance()`, so first-use controller construction, attachment setup, DAO access, and the attachment-directory snapshot cannot block the UI caller.

Background work returns results to the Android UI thread only at the presentation boundary. The controller layer continues to own revision database access; UI classes consume async results rather than reaching directly into Room-backed DAO methods for these interactive reads.

### 2. Revision history screen

`AyuMessageHistory` must stop querying revisions from its constructor.

The screen initiates one async history snapshot load after its UI lifecycle is ready. That background snapshot contains the revision list and the attachment-directory filename snapshot used to resolve saved media. While a load is pending, it must not flash the normal “no messages” state as though the result were known.

Each reload receives a monotonically increasing request generation. Only the newest generation may replace `messages`, rebuild `messageObjects`, update `rowCount`, or change the empty state.

A result delivered after fragment destruction or after a newer load starts is ignored.

### 3. Chat message context menu

The revision-presence lookup must occur before the Ayu history menu decision but must not execute Room work on the calling UI thread.

When all cheap baseline preconditions for the history item fail, menu construction proceeds synchronously and no revision query is issued.

When those preconditions pass, menu construction is deferred until the async revision-presence result is available. The callback resumes menu construction with a resolved boolean rather than re-querying the database.

A stale result must not open a menu for a view/message that is no longer the active long-press target.
### 4. Message read-date details

`MessageDetailsActivity` must not perform `SpyDao` queries while binding the read-date row.

When read-date tracking is disabled, no read-date database query is started and the existing disabled-feature behavior is preserved.

When enabled, the screen starts one background resolution for the selected message. Resolution preserves baseline precedence: use `SpyMessageRead.entityCreateDate` when present, otherwise `SpyMessageContentsRead.entityCreateDate`, otherwise no timestamp.

Until resolution finishes, the value may use a neutral loading placeholder rather than incorrectly presenting “unknown” as a final answer. After completion, only the affected row is refreshed.

A result for another message/account/dialog or a destroyed activity is discarded.

### 5. Error behavior

A background DAO exception is logged through the existing `FileLog` path and resolves to a safe no-data result for that request. It must not crash the UI thread.

Errors must not be cached as permanent truth. A later reload or menu attempt may retry the query.

Database close/import synchronization continues to be provided by the existing `LockedDao` proxy. The async layer must not cache raw DAO instances across calls.

## Enforcement boundary

`.allowMainThreadQueries()` remains in `AyuData` during P1. Removing it now would convert any still-unknown main-thread DAO call into a runtime crash and would exceed this subproject’s verified scope.

P1 acceptance instead requires that the three confirmed interactive paths above no longer perform synchronous Ayu DAO reads. Global removal of `allowMainThreadQueries()` becomes eligible only after a later complete call-site audit plus device/runtime validation.
## Test strategy

The implementation plan must create pure-Java seams where needed so coordination behavior can be tested with JUnit 4 without adding Robolectric solely for this work.

Required regression cases:

1. Latest revision-history request wins when callbacks complete out of order.
2. A destroyed/inactive history view ignores a late result.
3. Revision-history success produces the same ordered revision list and attachment filename snapshot supplied by the background load.
4. History-query failure resolves safely without making a stale earlier result current.
5. Chat-menu ineligible messages do not issue a revision-presence query.
6. Chat-menu eligible message with revisions receives `hasRevisions=true`; without revisions receives `false`.
7. A stale chat-menu query cannot open a menu for a different message or target view.
8. Read-date resolution prefers message-read timestamp over contents-read timestamp.
9. Read-date resolution falls back to contents-read when message-read is absent.
10. Disabled read-date tracking issues no spy query.
11. A stale read-date result cannot overwrite state for a different selected message.
12. DAO failure is logged/safely surfaced as no-data and remains retryable.

After focused tests pass, run the complete baseline unit suite: `./gradlew :TMessagesProj:testNormalDebugUnitTest` with JDK 21 and the canonical SDK.

The final implementation diff must also be inspected to confirm there is no new `Thread`, `Executors.new*`, scheduled polling, Room schema change, or feature-default change introduced by P1.

## Acceptance criteria

P1 is complete only when all of the following are true:

- `AyuMessageHistory` performs no synchronous revision DAO query or attachment-directory listing from constructor/UI update code.
- `ChatActivity.createMenu(...)` performs no synchronous `hasAnyRevisions(...)` Room query.
- `MessageDetailsActivity` row binding performs no synchronous spy/read-date DAO query.
- Final history contents, history-menu eligibility, and read-date precedence match baseline behavior.
- Stale async callbacks are lifecycle-safe.
- Async entrypoints enqueue work before first-use `AyuMessagesController.getInstance()` or attachment filesystem access.
- No new persistent background resource was introduced.
- Focused tests and the full normal-debug unit suite pass from a fresh verification run.

Real-device jank, battery, and thermal claims are deferred to the program-level device-validation gate.