# P4A — AyuFilter Hot-Path Exclusion Cache Design

**Status:** Approved 2026-10-02
**Program:** NagramXF Performance V2
**Golden behavioral baseline:** `30dcd6ce7b5b0279fa86f302ca61cd272bfa3068`
**Implementation base after P3:** `73b7c6436b9625741c858ddd4f4ea1deb4f3c46b`

## Intent

Remove confirmed repeated parsing/copy allocation from the feature-enabled `AyuFilter` hot path while preserving filter results, invalidation behavior, persistence format, and Room-loading semantics.

P4 is deliberately split:

- **P4A:** cache-hit/allocation hygiene covered by this spec;
- **P4B:** first-use Room I/O isolation, to be designed separately after P4A.

P4A does not claim lower battery drain, temperature, or frame time before final matched device measurements.

## Baseline evidence

`NaConfig.regexFiltersEnabled` defaults to `false`, so P4A is a feature-path optimization.

When enabled, `AyuFilter.isFiltered(...)` currently performs these steps in order:

1. feature/outgoing/skip checks;
2. `isDialogExcluded(dialogId)`;
3. `AyuFilterCache.get(...)`;
4. only on cache miss, text extraction and regex/filter matching.

`isDialogExcluded()` calls `getExcludedDialogs()` on every request. That method creates a new `HashSet`, creates a new `Gson`, reads the serialized config string, parses JSON into `Long[]`, and populates the set.

Therefore even an existing message/group LRU hit still pays JSON parsing and temporary collection allocation before the cache lookup.

On cache miss, `isFilteredInternal(...)` and `findFilteredRanges(...)` currently call the public `getExcludedSharedFilterIds(dialogId)`, which returns a new defensive `HashSet` from the already cached DB-backed exclusion map. Internal matching does not mutate that set, so the copy is allocation-only overhead.

The cold paths remain different problems:

- `getRegexFilters()` can lazy-load Room;
- `getChatFilterEntries()` can lazy-load Room;
- the shared-exclusion map can lazy-load Room through `getExcludedFilterEntries()`.

P4A leaves those first-use Room reads untouched. P4B will decide how to isolate them without changing first-render filtering semantics.

Repository audit found direct writes of `regexFiltersExcludedDialogs` in `AyuFilter.setDialogExcluded(...)` and `clearAllFilters()`. Generic configuration restore/import can still change the serialized value indirectly, so the cache must detect raw-value changes rather than relying only on explicit invalidation callbacks.

## Behavior contract to preserve

### Feature-disabled path

When `regexFiltersEnabled == false`, `isFiltered(...)` must continue returning before any exclusion snapshot, Room filter load, regex matching, or message-cache work.

P4A must not change the default value of any regex/filter option.

### Exclusion-before-cache ordering

`isDialogExcluded(dialogId)` must remain before `AyuFilterCache.get(...)` in `isFiltered(...)`.

P4A does not move the LRU lookup ahead of exclusion checking, because doing so can return a previously cached filter result after exclusion state changes through a path that has not yet cleared that message entry.

### Excluded-dialog persistence and visibility

The serialized config format remains the existing JSON array of `Long` dialog IDs.

A cached dialog snapshot is keyed by the exact serialized config string currently returned by `NaConfig`. If that raw string differs from the snapshot key, the cache must parse the new value before answering.

This raw-value key rule preserves visibility of configuration changes even if they arrive through a generic settings restore/import path that does not call an `AyuFilter` invalidation method.

Malformed/null serialized data preserves baseline behavior: the parser failure is logged by `AyuFilter`, the effective exclusion set is empty for that lookup, and **no snapshot is published for the failed raw value**. A later call with the same raw value retries parsing/logging, matching baseline retry behavior; changed raw values and explicit invalidation also retry.

### `setDialogExcluded(...)`

The setter keeps its current observable contract:

- start from the current effective exclusion set;
- add/remove the requested dialog;
- do nothing when membership does not change;
- when changed, serialize and persist the new set;
- clear that dialog's `AyuFilterCache` entry set exactly as baseline does.

After a successful persistence write, P4A must publish the already-known new snapshot immediately so the next read avoids reparsing. The published snapshot must not alias a mutable set owned by the caller.

### Rebuild and clear semantics

`rebuildCache()` must invalidate the new exclusion snapshots together with the existing filter/message caches. It must keep posting `regexFiltersUpdated` exactly as before.

`clearAllFilters()` keeps its current DB deletes and config writes. After the method completes, neither the dialog snapshot nor the shared-exclusion snapshot is allowed to expose pre-clear values.

P4A does not add a new notification, persistence write, thread, queue, or asynchronous delay to invalidation.

### Shared-filter exclusion API

The public `getExcludedSharedFilterIds(long)` contract remains defensive: callers receive a mutable copy and cannot mutate internal cached state.

Internal read-only matching code uses a private no-copy view of the cached set. That view must never be returned through a public API or mutated after publication.

## Chosen architecture

Introduce one package-private pure-Java helper, `AyuFilterExclusionSnapshots`, owned lazily by `AyuFilter`.

The helper does not know about Android, Telegram, Room, `NaConfig`, Gson, dialogs, messages, or filter semantics. It only owns immutable/copy-on-write exclusion snapshots and cache invalidation.

For excluded dialogs it stores:

- the serialized raw-value key used to build the snapshot;
- an unmodifiable `Set<Long>` snapshot.

For shared-filter exclusions it stores a deeply copied, read-only `Map<Long, Set<String>>` snapshot created from the existing DB-backed map loader.

The helper API is intentionally narrow:

- `Set<Long> dialogs(String serialized, Function<String, Set<Long>> loader)`;
- `void publishDialogs(String serialized, Collection<Long> values)`;
- `void invalidateDialogs()`;
- `Set<String> sharedView(long dialogId, Supplier<Map<Long, ? extends Set<String>>> loader)`;
- `HashSet<String> sharedCopy(long dialogId, Supplier<Map<Long, ? extends Set<String>>> loader)`;
- `void invalidateShared()`;
- `void invalidateAll()`.

The helper synchronizes cache-miss/publication paths. Cache-hit reads use volatile published snapshots and must not allocate a new collection. A cache-miss loader executes inside the same helper synchronization domain as publication/invalidation, so an invalidation that races an in-flight load cannot be lost behind a stale publication.

A loader exception is not swallowed by the helper and must not publish a new snapshot. `AyuFilter` remains responsible for its existing logging/fallback semantics.

## Production integration

`AyuFilter` owns the helper through a nested lazy holder so merely loading `AyuFilter` while regex filtering is disabled does not allocate the snapshot object.

`isDialogExcluded(dialogId)` reads the current serialized exclusion string, asks the helper for the corresponding snapshot, and performs `contains(dialogId)` on that snapshot.

The JSON parser remains in `AyuFilter`, preserving the current Gson format and `FileLog` behavior. Successful parsing occurs only when the serialized raw value differs from the cached snapshot key or after invalidation. Parse exceptions escape the helper without publication; `AyuFilter` catches/logs them and uses an empty set for that lookup. `setDialogExcluded(...)` likewise falls back to an empty mutable set after the same logged parse failure, preserving baseline repair behavior when it then persists a changed value.

`setDialogExcluded(...)` copies the current snapshot before mutation, persists only on a membership change, publishes the resulting snapshot after the config write, and keeps the existing `AyuFilterCache.clearDialog(dialogId)` call.

For shared-filter exclusions, P4A keeps the existing lazy DB-backed loader. The loaded map is published as a deep read-only snapshot once per invalidation epoch.

Internal methods that only inspect membership — including `isFilteredInternal(...)`, `findFilteredRanges(...)`, and `isSharedFilterExcluded(...)` — use `sharedView(...)` and do not allocate a defensive copy.

The existing public `getExcludedSharedFilterIds(long)` delegates to `sharedCopy(...)`, preserving its mutable-copy behavior.

Existing invalidation sites that currently set `excludedSharedFilterIdsByDialog = null` instead call `invalidateShared()` under the same surrounding synchronization/flow. The audited baseline has four such points: `rebuildCache()`, `addSharedFilterExclusion(...)`, `removeSharedFilterExclusion(...)`, and `removeExcludedSharedFilterEntries(...)`. P4A does not change when those invalidations happen or the accompanying `AyuFilterCache.clearAll()` behavior.

## Concurrency model

Snapshots are immutable after publication. Readers can hold an older snapshot briefly while another thread publishes a newer one, but no reader can observe a partially built collection. Cache-miss loading, publication, and explicit invalidation are serialized by the helper; when invalidation races a first load, invalidation must win for subsequent lookups rather than being overwritten by the older load.

A raw serialized dialog value is read before snapshot lookup. Because the snapshot key is that exact value, a later config value causes a miss/rebuild even if an explicit invalidation callback was skipped.

P4A does not add cross-thread callbacks or wait for background work. It therefore does not introduce stale async-result or lifecycle races.

The shared-exclusion DB map remains lazy and synchronous exactly as in baseline; P4A changes only how the already-loaded data is exposed internally.

## Explicit non-goals

P4A does not:

- move Room work off the calling thread;
- preload regex/filter data at startup;
- reorder `isDialogExcluded()` and `AyuFilterCache.get()`;
- change regex compilation, reversed-filter semantics, masking, hide-only-matched, or chat-filter precedence;
- change LRU cache sizes, keys, group behavior, or invalidation policy;
- change database schema, DAO queries, migrations, or config serialization;
- add a thread, executor, Handler, timer, polling loop, coroutine, or asynchronous callback;
- change feature defaults;
- claim a device-level performance improvement before measurement.

## Rejected alternatives

### Move the message LRU lookup before dialog exclusion

Rejected because it changes ordering and can surface stale cached filter state after exclusion changes. P4A optimizes the exclusion lookup itself instead.

### Cache excluded dialogs only until explicit setter invalidation

Rejected because generic configuration restore/import can change the serialized value outside the direct setter path. Raw-value keyed snapshots preserve visibility without reparsing unchanged JSON.

### Return the internal shared-exclusion set publicly

Rejected because callers can mutate global cached state. The public getter remains a defensive copy.

### Async-load every filter structure in P4A

Rejected because first-render behavior while Room data is unavailable needs a separate behavioral contract. That work belongs to P4B.

## Required regression coverage

Pure helper tests must cover:

1. first dialog lookup invokes the loader once and returns the loaded membership;
2. repeated lookup with the same serialized value reuses the same published snapshot without invoking the loader again;
3. a different serialized value forces a reload before membership is answered;
4. `invalidateDialogs()` forces a reload even when the raw value is unchanged;
5. `publishDialogs()` defensively snapshots its input, so later caller mutation cannot change cached membership;
6. a dialog-loader exception publishes nothing and a later call with the same serialized key retries the loader;
7. published dialog snapshots are read-only to callers;
8. first shared lookup loads/publishes one deep snapshot;
9. repeated internal shared lookup returns a no-copy read-only view without reloading;
10. mutation of the source map/set after publication cannot alter the shared snapshot;
11. the internal shared view rejects mutation;
12. `sharedCopy()` returns a mutable independent copy and mutations do not affect the internal view;
13. `invalidateShared()` forces the next shared lookup to reload;
14. missing shared-dialog entries produce an empty read-only view while public copies remain independently mutable;
15. `invalidateShared()` racing an in-flight first load cannot be lost: after the invalidation returns, the next lookup must load a fresh snapshot rather than reuse the pre-invalidation data.

Integration/parity verification must additionally prove:

- `isFiltered(...)` keeps the same feature/outgoing/skip checks and keeps `isDialogExcluded()` before `AyuFilterCache.get()`;
- `regexFiltersEnabled` still defaults to `false`;
- `AyuFilterCache.java` is unchanged;
- `loadSharedFilters()`, `loadChatFilterEntries()`, and `getExcludedFilterEntries()` keep their existing Room behavior and bodies;
- `setDialogExcluded(...)` still persists only on membership change and still clears the target dialog cache;
- public `getExcludedSharedFilterIds(long)` remains a defensive copy;
- shared-exclusion add/remove/filter-removal/rebuild invalidation sites still invalidate the shared snapshot at the same logical points;
- no new asynchronous or persistent work is introduced.

## Implementation boundary

Expected runtime changes are limited to:

- new package-private `AyuFilterExclusionSnapshots` pure-Java helper;
- exclusion snapshot ownership/invalidation in `AyuFilter`;
- cached dialog parsing keyed by the serialized config value;
- private no-copy shared-exclusion views for internal read-only matching;
- preserving the existing public defensive-copy API.

No other production class requires a behavior change in P4A. If implementation requires touching `AyuFilterCache`, Room DAO/schema code, UI cells, `MessageObject`, notification logic, or configuration defaults, execution stops and the design must be revisited.

## Verification gate

Before P4A can be committed as complete:

1. focused `AyuFilterExclusionSnapshots` tests pass;
2. `:TMessagesProj:testNormalDebugUnitTest` passes fresh on the final diff;
3. Java compilation passes with JDK 21 and the canonical Android SDK;
4. `isFiltered(...)` ordering and feature guards remain unchanged;
5. unchanged serialized dialog config does not invoke the parser more than once per snapshot epoch;
6. internal shared membership checks do not allocate defensive copies after snapshot load;
7. the public shared-ID getter still returns an independent mutable copy;
8. cold Room loader bodies and `AyuFilterCache.java` remain unchanged;
9. no new executor/thread/Handler/polling/timer/async callback exists;
10. no schema, migration, config format, or feature-default change exists;
11. `git diff --check` passes and the working tree is clean after commit.

## Success criteria

P4A succeeds when a warm feature-enabled filter path with unchanged exclusion configuration no longer reparses the excluded-dialog JSON and internal shared-exclusion membership checks no longer clone the cached set, while all filter outcomes and invalidation contracts remain unchanged.

The accepted pre-device performance statement is limited to those structural allocation/parsing removals. P4A does not establish a battery, thermal, or jank improvement by itself.

## P4B boundary

After P4A is complete, P4B will separately design first-use Room isolation for shared filters, per-chat filters, and shared exclusions. P4B must define what the UI/message path does before those snapshots are ready; P4A intentionally makes no decision on that behavior.

## Integration policy

P4A is developed from reviewed P3 HEAD and committed independently. It is not merged into `dev`, released, installed, or device-profiled as part of this subproject.

After this spec is approved, the next artifact is a detailed implementation plan. Product code remains blocked until that plan is also reviewed and approved.
