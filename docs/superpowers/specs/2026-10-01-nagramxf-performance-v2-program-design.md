# NagramXF Performance V2 Program Design

**Status:** Approved 2026-10-01
**Baseline:** `30dcd6ce7b5b0279fa86f302ca61cd272bfa3068` (`1251`)
**Repository:** `trangkyanh17/NagramXF`

## Intent

Rebuild the NagramXF optimization effort from the clean upstream baseline, with the primary goals of lower unnecessary CPU work, lower battery and thermal load, smoother UI behavior, and fewer concurrency-related defects without feature loss.

The previous optimization branch is evidence only. No previous performance patch is assumed correct and no performance commit is inherited automatically.

## Non-negotiable constraints

- Preserve user-visible features and existing protocol behavior unless a later spec explicitly changes them.
- Never infer that a timer, thread, query, or allocation is harmful from grep frequency alone; trace its activation conditions and call path first.
- Separate default-path costs from feature-enabled costs.
- Preserve exact behavior contracts before replacing scheduling, waiting, database, push, or plugin mechanisms.
- Use TDD for every behavior-changing implementation task.
- Every accepted subsystem change must end in a focused commit after fresh verification.
- Do not use Rust, compiler flags, or broad rewrites merely because they appear faster in theory.
- Real-device battery, thermal, frame, and long-session validation is a final program gate, not a substitute for per-task regression testing.
## Baseline evidence

The clean baseline has been verified with `:TMessagesProj:testNormalDebugUnitTest` under JDK 21 and the canonical Android SDK. Result: 29 tests, 0 failures, 0 errors, build successful.

That result establishes a regression baseline only. It does not prove runtime performance because test coverage is small relative to the repository and does not exercise device power, frame timing, or long-lived Android lifecycle behavior.

Confirmed architectural observations from the baseline:

- `AyuData` enables Room `allowMainThreadQueries()`.
- `AyuMessageHistory` performs a synchronous revision query and attachment-directory `list()` from its UI construction/update path.
- `ChatActivity.createMenu(...)` synchronously calls `hasAnyRevisions(...)` while constructing the message menu.
- `MessageDetailsActivity` synchronously performs two spy/read-date DAO lookups while binding a UI row.
- `DummyMessageWaiter` polls at 25 ms while waiting for message state transitions.
- `AyuWorker` uses a 3-second fixed-delay loop when its Ghost-mode behavior is activated.
- `MediaStreamingProvider.onCreate()` starts a `HandlerThread` when the provider is created.
- `PluginsWatchdog` polls every second, but the plugin engine defaults to disabled.
- The local push fallback can schedule a 10-minute `RTC_WAKEUP`, but only when the selected push provider does not provide services and local push is enabled.

## Classification rule

Every candidate optimization is classified before implementation as one of:

1. **Default-path** — active during ordinary app use with default settings.
2. **Feature-path** — active only while a specific optional feature is enabled or invoked.
3. **Burst-path** — expensive work triggered by an explicit user operation, such as transcription.
4. **Infrastructure-only** — build, CI, signing, or developer tooling that does not directly affect runtime performance.
## Program decomposition

The optimization program is deliberately split into independently reviewable subprojects. Each subproject gets its own written design and implementation plan before product code changes.

### P1 — Ayu DB/UI I/O isolation

Remove confirmed Room work from UI-critical paths without changing the final data shown or menu capabilities. Reuse existing background execution facilities where possible; do not add a new permanent polling loop or thread pool without evidence that it is necessary.

### P2 — Waiter/event synchronization

Replace `DummyMessageWaiter` polling with event-driven or blocking synchronization while preserving timeout, cancellation, upload/send ordering, and observable failure behavior.

### P3 — Ghost/AyuWorker scheduling

Eliminate unnecessary fixed-delay wakeups while preserving the baseline Ghost/offline packet contract exactly. The previous AYU patch is explicitly rejected as an implementation reference because it changed `requestLastSeenUpdate()` semantics.

### P4 — Filter hot-path isolation

Audit regex/filter cache misses, lazy Room loads, repeated matching, and UI-cell call frequency. Optimize only after distinguishing feature-disabled, cache-hit, and cache-miss behavior.

### P5 — Lazy lifecycle resources

Avoid creating resources such as `MediaStreamingProvider` worker threads before they are needed, while preserving ContentProvider and streaming behavior.

### P6 — Optional plugin/runtime scheduling

Replace global polling only where plugin-engine lifecycle and freeze detection contracts can be tested. Plugin-engine-off behavior must remain effectively zero-cost.

### P7 — Burst concurrency and build/runtime hygiene

Bound transcription or similar burst concurrency where evidence shows thread/resource amplification. Build warnings, CI action upgrades, signing, and reproducibility remain separate infrastructure work unless they directly affect shipped runtime behavior.
## Per-subproject gate

Every subproject must satisfy this sequence before its commit is considered acceptable:

1. Trace activation conditions and call graph from the clean baseline.
2. State the behavior contract and invariants in the subproject spec.
3. Add or identify tests that fail when the contract is broken.
4. Make the smallest implementation change that addresses the measured structural issue.
5. Run the focused test set and verify the intended failure turned green.
6. Run the full `:TMessagesProj:testNormalDebugUnitTest` suite.
7. Run the appropriate build/static checks for files touched.
8. Inspect the diff for unrelated refactors, feature loss, lifecycle leaks, and new persistent background work.
9. Commit the subsystem independently.

If a subproject cannot define a stable behavior contract, implementation stops at investigation rather than guessing.

## Measurement policy

Static evidence is used to select candidates, not to claim battery or thermal gains. Claims must be scoped to what was actually verified.

Allowed pre-device claims include:

- a polling loop no longer exists;
- a Room query no longer executes on the UI path;
- a worker is no longer created before first use;
- executor concurrency is explicitly bounded;
- a behavior regression test passes.

Battery percentage, power draw, thermal improvement, frame-time improvement, and long-session stability require final real-device measurement and must not be inferred from code structure alone.

## Branch and history policy

- `30dcd6ce` remains the golden performance baseline.
- New performance implementation branches start from that baseline unless a reviewed integration branch is explicitly selected later.
- Old AYU optimization commits are not cherry-picked.
- CI/signing fixes may be reintroduced separately only after confirming they do not alter runtime behavior.
- Each completed subsystem is committed separately to permit clean comparison and rollback.
## Final program acceptance

The program is ready for final device validation only after all accepted subprojects are integrated and their regression suites pass together.

Final device validation must compare the clean baseline and optimized build under matched conditions and should cover at least:

- idle/background behavior;
- active chat scrolling and message menu interactions;
- Ghost mode with offline-after-online enabled;
- regex filtering enabled and disabled;
- media streaming;
- plugin engine enabled and disabled;
- transcription/burst workloads;
- notification delivery for the selected push configuration.

The final verdict must report measured results and any confidence limits rather than claiming a universal percentage improvement.

## Success criteria

This design succeeds if the resulting optimization history is auditable: each runtime change has a known activation path, preserved behavior contract, regression evidence, isolated commit, and no unsupported performance claim.

The first implementation subproject is **P1 — Ayu DB/UI I/O isolation**, specified separately in `docs/superpowers/specs/2026-10-01-ayu-db-ui-io-design.md`.