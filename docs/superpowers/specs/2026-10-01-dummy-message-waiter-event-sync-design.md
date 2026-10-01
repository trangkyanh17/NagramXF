# DummyMessageWaiter Event Synchronization Design

**Status:** Proposed for user review
**Program:** NagramXF Performance V2 — P2
**Behavioral baseline:** `30dcd6ce7b5b0279fa86f302ca61cd272bfa3068`
**Cumulative implementation base:** `6a87d4b7c2e68c9ae52693ee048b8af6565ce70c`

## Goal

Remove the 25 ms polling loops and dedicated five-minute watcher thread from `DummyMessageWaiter` while preserving Ayu Forward send/upload ordering, message identification, completion, timeout, interruption, deletion, and failure-observation behavior.

P2 is a burst/feature-path optimization. `DummyMessageWaiter` is used only through `AyuSequentialUtils.dispatchSendSync(...)`, whose product call sites are in `AyuForward`. It is not an idle/default-path worker.

## Confirmed baseline behavior

`DummyMessageWaiter` currently performs two polling phases:

1. `trySetSendingId(...)` repeatedly calls `SendMessagesHelper.getSendingMessageIds(dialogId)` every 25 ms for up to 3.5 seconds.
2. `startQueueWatcher(...)` creates a daemon `Thread` which calls the same queue snapshot every 25 ms for up to 300 seconds.

At the worst configured duration, the watcher may wake roughly 12,000 times for one operation, in addition to the lookup loop.

The waiter also subscribes to `messageReceivedByServer`, `messageSendError`, `messageReceivedByAck`, and `messagesDeleted`. Completion can therefore arrive before `sendingId` has been discovered; the current implementation stores such IDs in `alreadySent` and reconciles them later.

`AyuSequentialUtils.dispatchSendSync(...)` captures the pre-dispatch pending IDs, subscribes the waiter, posts the send action to the UI thread, identifies the new local sending ID, optionally passes that ID to `DummyFileUploadWaiter`, then waits for upload and message completion.

## Event evidence already present in Telegram core

`SendMessagesHelper` emits `NotificationCenter.sendingMessagesChanged` for ordinary non-scheduled, non-ephemeral pending send/upload count changes. The existing `getSendingMessageIds(dialogId)` snapshot already merges both `sendingMessages` and `uploadMessages`.

`messageReceivedByServer` consistently includes:

- argument 0: old/local message ID;
- argument 3: dialog/peer ID;
- a scheduled flag later in the argument list.

`messageReceivedByAck` and `messageSendError` carry the local message ID. Their observed send paths post the notification before removing the message from the sending map, so a one-time queue reconciliation during that event can still discover the candidate.

Scheduled and ephemeral paths can skip `sendingMessagesChanged` on map changes, but their terminal send paths still use the subscribed server/ack/error notifications. This is why P2 must use both queue-change and terminal events rather than depending on `sendingMessagesChanged` alone.

## Behavioral invariants

P2 must preserve the following externally relevant contracts:

- Capture the message created by the current dispatch, not one of the IDs already pending before dispatch.
- Keep the baseline rule of selecting the first newly observed pending ID when multiple new candidates are present.
- Subscribe before the target dispatch can emit completion notifications.
- A completion notification that races ahead of ID discovery must not be lost.
- Once the selected message is acknowledged, received by server, errors, is deleted, or its observed pending queue drains back to the baseline level, the message waiter releases.
- Before `sendingId` is known, baseline terminal-event handling is intentionally loose: ACK/server/error IDs are stored in `alreadySent` without dialog/candidate proof. The watcher then treats any stored terminal ID as evidence that a new pending send was observed and may release once the target dialog queue is back at the baseline count. P2 must preserve this pre-identification account-level behavior even when an event later proves unrelated; tightening it is a separate correctness change.
- `messageSendError` continues to mark the waiter failed with the same account-level semantics as baseline: while subscribed, even an error whose ID has not yet been proven to be the selected candidate may set the failure flag. P2 must not silently tighten that behavior even though current production code does not consume `hasFailed()`.
- The 3.5-second period remains the maximum synchronous wait used to obtain a message ID for upload coordination.
- The overall `SyncWaiter.await()` timeout remains 300 seconds.
- Interrupts preserve the thread interrupt flag and release subscriptions as baseline does.
- `DummyFileUploadWaiter` still receives the discovered local message ID when identification succeeds within the lookup window.
- No send, upload, schedule, notification, or Ayu Forward feature default changes are introduced.

## Architecture

### 1. Pure message-wait state machine

Extract a package-local/pure-Java state seam (working name `MessageWaitState`) that owns:

- baseline pending IDs and baseline pending count;
- selected `sendingId`;
- whether a new pending message has ever been observed;
- terminal IDs observed before selection;
- failure state;
- whether the logical target is complete.

The state machine accepts snapshots/events and returns decisions such as `identifiedId`, `shouldRelease`, and `failed`. It does not own observer subscription or the `SyncWaiter` release latch; `SyncWaiter.release()` remains the single owner of actual release/unsubscription. It does not import Android, `NotificationCenter`, or `SendMessagesHelper`.

This seam exists so race ordering can be tested deterministically without sleeping or Robolectric.

### 2. Queue changes become signals, not polling triggers

`DummyMessageWaiter` subscribes to `NotificationCenter.sendingMessagesChanged` in addition to its current notifications.

On `sendingMessagesChanged`, it performs exactly one `getSendingMessageIds(dialogId)` snapshot and feeds it into the state machine. If a new non-baseline ID appears, that ID becomes `sendingId`. If a new pending message was observed and the queue later falls back to the baseline pending count, the waiter releases just as the old watcher did.

No periodic queue watcher is created.

### 3. Dispatch-completion reconciliation

`AyuSequentialUtils.dispatchSendSync(...)` wraps the existing UI-thread dispatch. A waiter hook such as `onDispatchCompleted()` runs from a `finally` boundary around the existing `action.dispatch()` call on that same UI-thread turn, so reconciliation is not skipped if the dispatch throws. P2 must not swallow or translate the dispatch exception; existing exception propagation remains unchanged.

That hook performs one queue snapshot. In the common path, Telegram's send call has already inserted the local pending message, so identification happens immediately without any polling wakeup.

The send action itself remains on the UI thread. The caller that performs synchronous sequencing remains off the UI thread, as required by the existing `SyncWaiter` architecture.

### 4. Bounded blocking identification wait

Replace the 25 ms lookup loop with a one-shot blocking primitive (`CountDownLatch` or equivalent condition) that is signalled when the state machine discovers the sending ID or when a terminal event can be proven to belong to the target candidate. Ambiguous pre-identification ACK/error/server events are recorded but **must not** end the 3.5-second identification window early.

The sequencing thread waits at most `LOOKUP_TIMEOUT_MS = 3500` for candidate identification. It consumes no CPU while waiting. This preserves the baseline opportunity for a target pending ID to appear after an unrelated/ambiguous terminal event.

Immediately before declaring the 3.5-second identification window expired, perform **one final queue reconciliation snapshot**. This preserves the baseline chance of discovering a delayed/scheduled local ID that appeared near the timeout boundary without reintroducing polling. Mark the state as `lookupWindowExpired` only after this final snapshot.

If the final snapshot still does not identify a candidate, apply the old watcher semantics to any terminal IDs already recorded: when at least one pre-identification terminal ID exists and the target-dialog queue is at or below the baseline pending count, release is now allowed. Before `lookupWindowExpired`, that ambiguous queue-count release is forbidden. If neither identification nor baseline-style release is possible, the waiter remains subscribed; later queue/server/ack/error/deletion events may complete it. P2 does not introduce a recurring fallback.

If the identification wait is interrupted, preserve the interrupt flag and release the waiter, matching the baseline interrupt behavior.

### 5. Terminal-event reconciliation

For `messageReceivedByAck` and `messageSendError`, when `sendingId` is still unknown, perform one queue snapshot before classifying the event. In the traced Telegram paths the notification is posted before pending removal, allowing the local ID to be discovered without polling.

For `messageReceivedByServer`, a matching dialog may use its local ID as a direct candidate-identification signal **only when that local ID was not present in the captured baseline ID set**. This covers scheduled/ephemeral/event-silent sends without depending on `sendingMessagesChanged`. A server event from another dialog must still be recorded as a pre-identification terminal ID when `sendingId == 0`, because baseline `alreadySent` does so; it is not allowed to become the selected ID unless a target-dialog queue snapshot independently exposes it as a non-baseline candidate.

For `messageReceivedByAck` and `messageSendError`, the event ID is likewise retained when `sendingId == 0`. After recording such an early terminal ID, perform one target-dialog queue reconciliation. A candidate exposed by that snapshot may be selected immediately, but an ambiguous terminal ID by itself must not release the waiter before `lookupWindowExpired`; after expiry, queue-at/below-baseline may complete it exactly as the old watcher did with non-empty `alreadySent`.

For `messagesDeleted`, preserve the existing dialog matching rule before retaining deleted IDs; unrelated deletion remains ignored. Baseline pre-existing queue IDs remain excluded from candidate selection. Once `sendingId` is known, only the selected ID (or the baseline queue-drain rule) may complete the target waiter.

### 6. Completion and upload ordering

`dispatchSendSync(...)` keeps the current high-level order:

1. prepare/capture baseline;
2. subscribe message/upload waiters;
3. post UI send action;
4. wait up to 3.5 seconds for event-driven message-ID identification;
5. pass a discovered ID to `DummyFileUploadWaiter`;
6. await upload when requested;
7. await message completion when requested.

Only the implementation of step 4 and ongoing message completion tracking changes. The public method signature and return behavior remain unchanged in P2.

## Concurrency rules

- No new `Thread`, executor, scheduled task, timer, or polling loop may be introduced.
- Notification callbacks and dispatch-completion reconciliation may arrive on the UI thread while the sequencing caller blocks on a latch off the UI thread.
- Shared state must therefore use a synchronized state-machine boundary or atomic fields with a single documented ownership rule.
- Never block the Android UI thread waiting for identification or completion.
- `unsubscribe()` remains idempotent through `SyncWaiter.release()`.
- Signal/latch completion must be idempotent so server + queue-change races cannot release twice.

## Error and timeout behavior

- Queue snapshot exceptions are treated like baseline lookup exceptions: they do not crash the send flow. A later event may retry reconciliation.
- A lookup-window timeout is not itself the overall send timeout and does not force failure.
- The existing 300-second `SyncWaiter.await()` timeout remains authoritative for waiter timeout state.
- P2 does not change the fact that `dispatchSendSync(...)` currently returns `true` after its wait sequence; changing result semantics belongs to a separate behavior project.

## Required tests

The implementation plan must create deterministic JUnit coverage for at least:

1. Baseline IDs are never selected as the new send.
2. First newly observed pending ID is selected.
3. Dispatch-completion snapshot can identify the new ID without polling.
4. `sendingMessagesChanged` identifies a message inserted after dispatch returns.
5. Queue drain to baseline after observing a new pending message releases the waiter.
6. ACK after identification releases only when the ID matches.
7. Send error after identification marks failure and releases.
8. ACK/error arriving before identification is retained/reconciled and not lost.
9. Server completion with matching dialog directly identifies/releases a scheduled send when no queue-change event occurred.
10. A server completion from another dialog cannot become the selected target ID, but before identification it is retained with baseline `alreadySent` semantics and can contribute to queue-drain completion.
11. Matching `messagesDeleted` releases; unrelated deletion does not.
12. An ambiguous pre-identification ACK/error does not end the 3.5-second lookup window early even when the queue is currently at baseline.
13. Lookup-window timeout performs one final reconciliation snapshot, performs no repeated polling, then enables baseline `alreadySent + queue-count` release semantics for still-unidentified state.
14. Interrupted identification preserves interruption and releases subscription state.
15. Multiple queue/server signals are idempotent and cannot double-complete.
16. Upload coordination receives the selected ID when identified inside the 3.5-second window.
17. An unrelated pre-identification `messageSendError` preserves both the baseline failure-flag side effect and, **after lookup expiry**, the baseline possibility of queue-drain completion; after identification it cannot directly release unless its ID matches the selected target.
18. An unrelated pre-identification ACK preserves the same delayed `alreadySent`/queue-drain behavior without changing candidate selection.
19. Dispatch-completion reconciliation still runs if the dispatch action throws, without swallowing/changing that exception.
20. A matching-dialog server event whose local ID was already present in the baseline set cannot become the selected target candidate.
21. After a new candidate has been observed, returning to `currentQueueSize <= baselinePendingCount` preserves the old watcher release rule even if the selected ID is still present alongside fewer baseline IDs.

Focused tests must not use real 3.5-second or 300-second sleeps. Timeouts/signals must be exposed through small injectable/pure seams where necessary.

## Acceptance criteria

P2 is complete only when:

- `DummyMessageWaiter` contains no `Thread.sleep(25)`, 25 ms polling loop, or dedicated watcher `Thread`.
- There is no recurring queue scan; `getSendingMessageIds(...)` is called only on bounded setup/reconciliation events.
- `sendingMessagesChanged` is used for normal pending queue transitions.
- Scheduled/early-completion paths remain covered through terminal notification reconciliation.
- The 3.5-second identification and 300-second overall timeout contracts remain intact.
- Upload/message sequencing order remains intact.
- Required race-order tests pass.
- Existing normal-debug unit suite passes.
- No unrelated Telegram core behavior, feature defaults, schema, or infrastructure files are changed.

## Alternatives rejected

### Lower-frequency polling

Changing 25 ms to 100/250/1000 ms reduces wakeups but retains periodic work, adds latency, and leaves the five-minute watcher architecture intact. It does not solve the structural issue.

### `ScheduledExecutorService`/timer replacement

A scheduler would still wake periodically and adds another lifetime-managed background resource. This violates the P2 goal.

### Terminal notifications only

ACK/server/error notifications alone are insufficient for early local-ID handoff to the upload waiter and do not model queue-drain completion. Queue-change signalling is still required.

### Modify `SendMessagesHelper` to add a new Ayu-specific callback

This could expose the local ID directly but would couple Ayu forwarding to Telegram core internals and enlarge regression surface. Existing `sendingMessagesChanged` plus terminal notifications already provide the required signals, so P2 should stay in the Ayu sequencing layer unless implementation evidence disproves that assumption.

## Non-goals

P2 does not optimize `DummyFileDownloadWaiter`, `DummyFileUploadWaiter`, Ghost/AyuWorker scheduling, plugin watchdogs, push alarms, Room access, or general Telegram send architecture. It does not change Ayu Forward policy or bypass Telegram send restrictions.
