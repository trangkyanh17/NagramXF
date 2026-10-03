# P4B — AyuFilter Room Prewarm Design

**Status:** Approved 2026-10-03
**Program:** NagramXF Performance V2  
**Golden behavioral baseline:** `30dcd6ce7b5b0279fa86f302ca61cd272bfa3068`  
**Implementation base after P4A:** `cc1baf7be4a35f8ee4ccfdd26d477279a6dd7b2d`

## Intent

Reduce feature-enabled first-use Room work on UI/message hot paths by prewarming the three AyuFilter Room-backed snapshots on an existing background queue before normal rendering/filtering reaches them.

P4B preserves the exact filtering answer. It does **not** introduce fail-open, fail-closed, stale-until-ready, placeholder, delayed-hide, or deferred-download semantics.

The accepted optimization claim is deliberately narrow:

- after a successful prewarm, `isFiltered(...)` and other cache consumers reuse in-memory state without performing first-load Room work;
- prewarm itself runs off the UI thread on an existing Telegram queue;
- when the first consumer races prewarm or prewarm fails, baseline synchronous loading remains the correctness fallback.

P4B therefore improves the normal feature-enabled startup/mutation path without claiming that the first ever filter call can never block under every scheduling race.

## Why the fallback is required

Current first-load results are behavior-significant.

`AyuFilter.isFiltered(...)` is called from UI rendering and download decisions, including:

- `DialogCell` while selecting the visible dialog preview;
- `ChatMessageCell` while deciding whether a reply preview is masked;
- `DownloadController` while deciding whether media may auto-download.

Returning an incomplete answer while Room data is still loading can expose a message that should be filtered or permit a download that baseline would reject.

Blocking a UI caller on a newly introduced latch/future would move the SQL execution off-main but can preserve the same or worse visible stall. P4B rejects that as a misleading optimization.

The safe design is therefore **early background prewarm plus unchanged synchronous fallback**.

## Baseline evidence

At P4A HEAD:

- `regexFiltersEnabled` still defaults to `false`;
- when disabled, `isFiltered(...)` returns before any filter snapshot or Room access;
- when enabled, the first uncached filtering path can synchronously load:
  - shared filters via `RegexFilterDao.getShared()`;
  - per-chat filters via `RegexFilterDao.getAll()`;
  - shared-filter exclusions via `RegexFilterDao.getAllExclusions()`;
- P4A removed repeated excluded-dialog JSON parsing and internal shared-exclusion defensive copies, but intentionally did not move these first Room loads;
- `AyuData` still allows Room main-thread queries;
- `ApplicationLoader.postInitApplication()` initializes `NaConfig` before account/controller setup;
- the project already has `Utilities.globalQueue`, so no new executor/thread is needed.

The existing filter mutation paths already invalidate/rebuild caches synchronously after writes. P4B may schedule a one-shot prewarm after those invalidations, but must not change persistence order or visibility.

## Behavior contract to preserve

### Feature-disabled path

When `NaConfig.regexFiltersEnabled == false`:

- startup must not query filter Room tables for P4B;
- `AyuFilter.isFiltered(...)` must still return before filter-cache work;
- P4B must not allocate a dedicated worker, timer, executor, or recurring task.

A disabled installation therefore retains effectively zero P4B runtime cost beyond the existing configuration check.

### Filtering correctness

No caller may receive a different filtering answer merely because prewarm has not completed.

In particular P4B must not:

- treat "not loaded" as "no filters";
- expose replies/dialog previews temporarily and hide them later;
- allow an auto-download while filter state is unresolved;
- use a stale generation after a filter mutation;
- replace Room truth with legacy JSON merely to avoid waiting.

If cached state is not ready when a caller needs it, the caller follows the existing synchronous loader path.

### Room loader semantics

The bodies and fallback behavior of:

- `loadSharedFilters()`;
- `loadChatFilterEntries()`;
- `getExcludedFilterEntries()`;

remain behaviorally unchanged.

P4B changes **when** those loaders are invoked, not what they return.

Legacy JSON migration/fallback remains exactly where baseline keeps it.

### Persistence and mutation ordering

Existing writes remain synchronous and ordered as before.

Examples:

- `saveFilter(...)` writes Room/config then rebuilds;
- `saveChatFilterEntries(...)` writes Room/config then rebuilds;
- shared-exclusion add/remove writes Room then invalidates exclusion state and message cache;
- `clearAllFilters()` performs its existing deletes/config clears then rebuilds.

P4B may enqueue prewarm only after the existing invalidation point has happened.

### Cache invalidation wins over old preload work

A prewarm started for an older cache generation must never republish state after a later mutation/rebuild invalidated it.

P4B therefore needs an explicit generation/epoch token around background prewarm publication or an equivalent compare-before-publish rule.

Existing synchronous getters may still load and publish the current generation if they win the race.

### No new persistent background resource

P4B uses an existing queue and finite one-shot jobs only.

It must not add:

- a new `Thread`;
- a new `DispatchQueue`;
- an executor;
- a HandlerThread;
- polling;
- a timer;
- a coroutine loop;
- periodic refresh.

## Chosen architecture

Introduce a small package-private preload coordinator owned by `AyuFilter`.

The coordinator tracks:

- a monotonically increasing invalidation generation;
- whether a one-shot prewarm has already been scheduled for the current generation;
- no permanent thread/resource of its own.

`AyuFilter.schedulePrewarmIfEnabled()` performs only lightweight checks on the caller:

1. return immediately if regex filtering is disabled;
2. capture the current generation;
3. coalesce duplicate schedule attempts for that generation;
4. post one runnable to `Utilities.globalQueue`.

The background runnable loads the current-generation caches through dedicated preload entry points.

The preload path covers:

1. shared regex filters;
2. per-chat filters;
3. shared-filter exclusions.

The preloader must not fabricate alternate data structures. It populates the same cache ownership consumed by ordinary getters.

Before each publication, or before treating the generation as complete, it verifies that the captured generation still matches the current generation. If invalidation occurred, stale preload results are discarded or left unpublished.

## Startup trigger

After `NaConfig.init()` in `ApplicationLoader.postInitApplication()`, P4B schedules a prewarm only when regex filtering is already enabled.

This is intentionally early:

- configuration is available;
- the application context is available;
- ordinary dialog/message UI construction has not yet completed;
- the Room work executes on `Utilities.globalQueue`, not on the ApplicationLoader/UI caller.

P4B does not block `postInitApplication()` waiting for the preload.

## Mutation triggers

After a filter-state invalidation caused by a user mutation, P4B schedules a new one-shot prewarm for the new generation when filtering remains enabled.

Required trigger classes:

- `rebuildCache()` after shared/per-chat filter changes;
- shared-exclusion invalidation after add/remove/filter-removal;
- an explicit feature enable transition in regex-filter settings.

Duplicate calls in one generation are coalesced.

The existing cache invalidation remains authoritative; prewarm is only a follow-up optimization.

## Synchronous fallback

The ordinary getters remain able to load synchronously if their cache is empty.

This matters for:

- first call racing startup prewarm;
- queue congestion;
- prewarm exception;
- unusual direct calls before `ApplicationLoader` startup trigger;
- tests or processes that do not execute the normal application-init path.

The synchronous fallback must not wait for the prewarm runnable.

If it wins and populates the cache first, the background runnable should observe that state and avoid redundant publication where practical.

## Failure behavior

Prewarm exceptions are logged through existing loader behavior and must not mark unresolved data as successfully ready.

A failed background preload does not disable filtering and does not cache a false/empty answer solely because the preload failed.

The next ordinary access follows baseline behavior and can retry synchronously.

A later invalidation/new generation can schedule another background preload.

## Concurrency model

The design must satisfy these races:

### Startup prewarm vs first UI filter call

Either:

- background prewarm finishes first and the UI uses the warm snapshot; or
- the UI runs the baseline synchronous load and returns the exact result.

No third "not ready" answer exists.

### Prewarm vs rebuild/invalidation

Invalidation increments the generation before stale preload publication can be considered current.

An old runnable cannot overwrite the post-mutation generation.

### Two schedule requests in one generation

Only one prewarm runnable is required.

If two are posted due to a benign race, correctness still holds, but the implementation should coalesce them to avoid duplicate Room work.

### Synchronous fallback vs prewarm

Both may race to load. Publication must remain atomic and generation-aware.

The resulting visible state must correspond to the current generation, never a mixture of old and new tables.

## Scope boundaries

P4B runtime changes are expected to be limited to:

- `AyuFilter` preload scheduling/invalidation integration;
- one small pure-Java/package-private preload-generation coordinator if useful for deterministic tests;
- one startup hook in `ApplicationLoader`;
- the regex-filter settings enable path if needed for immediate prewarm.

No DAO, Room schema, entity, migration, download policy, message cell behavior, or regex matching semantics should change.

If implementation requires changing any filter decision before data is ready, execution stops and the design must be revisited.

## Explicit non-goals

P4B does not:

- remove `allowMainThreadQueries()` globally;
- convert the DAO to RxJava/Flow/LiveData;
- make filter writes asynchronous;
- introduce eventual-consistency filtering;
- use legacy prefs as a substitute for Room truth;
- change filter defaults;
- change regex matching or masking;
- change cache keys or `AyuFilterCache`;
- change database import/export behavior;
- solve unrelated stale-cache behavior after external database replacement;
- claim battery, thermal, frame-time, or percentage gains before device measurement.

Removing `allowMainThreadQueries()` is a repository-wide contract change and belongs to a separate project after all main-thread DAO callers are audited.

## Rejected alternatives

### Fail-open until preload completes

Rejected because filtered content/downloads could become visible temporarily.

### Fail-closed until preload completes

Rejected because legitimate content/downloads would be blocked temporarily.

### Block UI on a Future/CountDownLatch

Rejected because SQL would move threads while preserving or worsening the visible stall.

### Use legacy JSON as first-render truth

Rejected because Room is the current authoritative store and database import/direct state can diverge from legacy config.

### Create a dedicated filter executor

Rejected because `Utilities.globalQueue` already exists and the workload is finite/rare.

### Load all filters unconditionally at app startup

Rejected because regex filtering defaults to disabled; this would move feature-path cost into every launch.

## Required regression coverage

Tests must prove:

1. disabled scheduling performs no preload work;
2. enabled scheduling posts exactly one job for a generation;
3. duplicate schedule requests in one generation are coalesced;
4. invalidation advances the generation;
5. an old in-flight preload cannot mark the new generation ready;
6. a new generation can schedule another preload;
7. preload failure does not poison future scheduling/fallback;
8. ordinary synchronous getters remain available while preload is pending;
9. `isFiltered(...)` feature-disabled guard and exclusion-before-LRU ordering remain unchanged;
10. the three frozen Room loader bodies remain unchanged;
11. `AyuFilterCache.java` remains unchanged;
12. startup scheduling occurs only after `NaConfig.init()` and only when enabled;
13. no new thread/executor/Handler/timer/polling construct is added;
14. all existing filter mutation persistence/invalidation ordering remains intact.

## Verification gate

Before P4B can be committed as complete:

1. pure coordinator tests pass if a coordinator is introduced;
2. startup/mutation integration shape tests pass;
3. P4A focused tests still pass;
4. full `:TMessagesProj:testNormalDebugUnitTest` passes fresh;
5. `:TMessagesProj:compileNormalDebugJavaWithJavac` passes with JDK 21;
6. frozen P4/P4A method bodies required by this design match their approved baseline where specified;
7. `RegexFilters` still defaults to `false`;
8. `AyuFilterCache.java` SHA-256 is unchanged from P4A;
9. no DAO/schema/migration diff exists;
10. no persistent background resource exists;
11. `git diff --check` passes;
12. branch is pushed and local/remote HEAD match.

## Success criteria

P4B succeeds when feature-enabled startup and post-mutation flows proactively populate AyuFilter Room-backed caches on `Utilities.globalQueue`, so normal subsequent filter calls avoid first-load Room queries, while every scheduling race preserves the exact baseline filtering result through synchronous fallback.

The strongest accepted pre-device statement is:

> P4B adds feature-gated background prewarming and removes Room first-load work from filter hot paths whenever that prewarm completes before first use, without introducing an incomplete-data filtering state.

It does **not** claim that every possible first call is non-blocking.

## Integration policy

P4B is developed from verified P4A HEAD and committed independently.

It is not merged into `dev`, released, installed, or device-profiled as part of this design phase.

After this design is approved, the next artifact is a detailed implementation plan. Product code remains blocked until that plan is separately reviewed and approved.
