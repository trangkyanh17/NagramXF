# P7 — Bounded Transcription Burst Concurrency Design

**Status:** Approved 2026-10-04
**Program:** NagramXF Performance V2
**Implementation base after P6:** `132c8d8f87ecccf3e7a9778526675ba823d64161`
**Golden behavioral baseline:** `30dcd6ce7b5b0279fa86f302ca61cd272bfa3068`

## Intent

Bound the amount of simultaneous heavy custom-AI transcription work so a burst of transcription requests cannot create an unbounded number of worker threads, full-file audio buffers, Base64 payloads, and blocking HTTP calls.

P7 is intentionally limited to the custom transcription path implemented by `TranscribeHelper`.

It does not alter Telegram server transcription, translation, AI chat, media sending, generic Telegram thread pools, or build/CI infrastructure.

## Baseline evidence

At verified P6 HEAD:

- `TranscribeHelper` owns a static executor created with:

```java
Executors.newCachedThreadPool()
```

- repository search finds this as the only `newCachedThreadPool()` in `TMessagesProj/src`;
- three provider implementations submit heavy work to that same executor:
  - Cloudflare Workers AI;
  - Gemini;
  - OpenAI-compatible;
- each submitted task can perform some combination of:
  - round-video audio extraction through `MediaExtractor` / `MediaMuxer`;
  - `Files.readAllBytes(...)` for the complete audio file;
  - full Base64 encoding;
  - JSON construction containing the audio payload;
  - a synchronous OkHttp `.execute()` request;
- the transcription OkHttp client allows:
  - 120-second read timeout;
  - 120-second write timeout;
  - 180-second total call timeout;
- `TranscribeButton` has two custom-AI call sites into `TranscribeHelper.sendRequest(...)`;
- request bookkeeping is per message/transcription and does not provide a global concurrency ceiling across different messages.

The current cached executor therefore permits burst thread/resource amplification proportional to simultaneous user requests.

## Classification

P7 is a **burst-path resource bound**.

It is not a cold-start optimization and does not run unless the user invokes custom-AI transcription.

The target is structural:

- at most two custom transcription jobs execute heavy work concurrently;
- additional jobs wait in FIFO executor order;
- no transcription job is rejected solely because the concurrency limit is reached;
- executor worker threads disappear after an idle period.

No device-level battery, thermal, memory, or latency percentage is claimed before measurement.

## Chosen concurrency limit

`MAX_CONCURRENT_TRANSCRIPTIONS = 2`.

Rationale:

1. one job can hold an entire audio file byte array and Base64 expansion at the same time;
2. one job can occupy a synchronous network request for up to 180 seconds;
3. allowing two jobs preserves useful overlap for network-bound work;
4. capping at two prevents a rapid multi-message burst from scaling active heavy work one-thread-per-request.

P7 does not make this user-configurable. A configurable value would add policy/UI surface without evidence that users need to tune it.

## Executor architecture

Replace the cached executor with a lazily populated, bounded `ThreadPoolExecutor`:

```text
corePoolSize = 2
maximumPoolSize = 2
keepAlive = 60 seconds
workQueue = LinkedBlockingQueue
allowCoreThreadTimeOut(true)
```

Important behavior:

- constructing the executor does not prestart threads;
- the first submissions create workers on demand;
- no more than two jobs execute at once;
- additional jobs remain queued rather than rejected;
- core workers are allowed to time out after 60 seconds idle, returning the transcription worker count to zero when unused.

A small package-private pure-Java factory is preferred so this policy can be tested without loading Android-dependent `TranscribeHelper`.

Suggested class:

`tw.nekomimi.nekogram.helpers.TranscriptionExecutorFactory`

The factory owns only executor construction and constants. It must import only `java.util.concurrent.*`.

## Why an unbounded FIFO work queue is acceptable here

P7 is bounding **active heavy work**, not inventing a new rejection policy.

The queued `Runnable` captures path/provider/callback metadata, but the large operations occur inside the runnable only when a worker begins execution:

- media extraction;
- full file read;
- Base64 expansion;
- request body construction;
- synchronous network I/O.

Using an unbounded `LinkedBlockingQueue` therefore keeps active memory/network amplification bounded while preserving the baseline property that submitted transcription work is accepted rather than failed due to local queue capacity.

A bounded queue with rejection would require a new user-visible error contract and is outside P7.

## Behavior contract to preserve

### Provider selection

`sendRequest(...)` provider routing remains unchanged:

- AUTO;
- Workers AI;
- Gemini;
- OpenAI-compatible.

No credentials, endpoint, prompt, model, provider fallback, or account logic changes.

### Immediate validation errors

Errors detected before executor submission remain synchronous exactly as baseline:

- missing Cloudflare credentials;
- missing Gemini API key;
- missing OpenAI-compatible credentials.

P7 must not move these errors into the background queue.

### Submitted callback threading

For a request that reaches `executorService.submit(...)`, provider success/failure callbacks continue to originate from a transcription worker thread.

P7 does not reroute these callbacks to UI thread or another executor.

`TranscribeButton` remains responsible for its existing UI-thread posts.

### Request bodies and network behavior

P7 must not alter:

- audio extraction logic;
- file path fallback behavior;
- `Files.readAllBytes`;
- Base64 format;
- request JSON;
- endpoints;
- headers;
- OkHttp client selection;
- synchronous `.execute()`;
- HTTP timeout configuration;
- response parsing;
- exception text/flow.

The optimization changes only executor scheduling.

### Queue semantics

When more than two custom-AI transcription jobs are submitted:

- the first two may run concurrently;
- later jobs wait;
- all accepted jobs eventually run unless the process terminates;
- no caller is failed merely because two jobs are active.

P7 does not add cancellation for queued transcription jobs.

### Idle behavior

The executor object remains static, but its worker threads must be allowed to time out.

After the queue is empty and workers have been idle for the configured keepalive, the pool can return to zero live threads.

P7 does not add a timer, maintenance task, scheduler, or manual shutdown hook.

## Concurrency/race considerations

### Two simultaneous jobs

Both execute concurrently, preserving useful parallelism.

### Third or later job

It waits in the executor queue and begins when one of the two active workers becomes available.

### Provider mix

Workers AI, Gemini, and OpenAI-compatible jobs share the same global limit.

This is intentional because all three paths consume the same class of device resources: media/file memory work plus blocking network I/O.

### Long-running request

A long request may occupy one of the two worker slots until its existing network timeout/response completes.

P7 does not change those timeout values.

### Process/background lifecycle

No explicit executor shutdown is added. Core-thread timeout is the lifecycle mechanism.

This avoids introducing shutdown/restart races into a static helper used from multiple UI call sites.

## Test strategy

### Pure executor policy tests

Create tests for the factory without Android runtime dependencies.

Required deterministic cases:

1. freshly created pool has zero live workers;
2. core size is 2;
3. maximum size is 2;
4. keepalive is 60 seconds;
5. core-thread timeout is enabled;
6. work queue is `LinkedBlockingQueue`;
7. two blocked jobs can be active simultaneously;
8. a third blocked job remains queued while the first two are active;
9. after one active job is released, the third can start;
10. no `RejectedExecutionException` occurs for the third queued task.

Use latches/barriers only to control test execution; do not use real 60-second sleeps.

### Source-contract test

A narrow source test must prove:

- `TranscribeHelper` no longer calls `Executors.newCachedThreadPool()`;
- it obtains the executor from the pure factory;
- the three existing `executorService.submit(...)` sites remain present;
- provider routing and all three request method names remain;
- no second transcription executor is introduced.

## Frozen boundaries

P7 must not modify runtime behavior in:

- `TranscribeButton`;
- `HttpClient.transcribeInstance`;
- `NaConfig` transcription provider settings;
- Telegram server transcription;
- Translator/LLM translation flow;
- AI chat `Client`;
- MediaController;
- P1–P6 runtime files.

Expected runtime files are limited to:

- `TMessagesProj/src/main/java/tw/nekomimi/nekogram/helpers/TranscribeHelper.java`;
- one new pure-Java `TranscriptionExecutorFactory.java`.

If another runtime file becomes necessary, stop and revisit design.

## Similar burst candidates intentionally deferred

Audit also found other thread/executor creation across Telegram and AI features.

P7 does not optimize them by analogy alone.

In particular, the AI chat `Client` creates one single-thread executor per active request, but it also explicitly tracks, cancels, and shuts down those per-request executors. That lifecycle differs materially from the transcription cached pool and requires its own evidence before modification.

Generic Telegram media/camera/exoplayer pools are likewise outside NagramXF-specific P7 scope.

## Build/runtime hygiene boundary

The program mentions build/runtime hygiene, but build warnings, GitHub Action upgrades, signing, and reproducibility are infrastructure concerns unless they directly affect shipped runtime behavior.

No such dependency was established by this P7 audit.

Therefore this design does not mix CI/signing/build-warning cleanup into the transcription runtime change.

Those items remain separate follow-up work.

## Rejected alternatives

### Single-thread transcription executor

Rejected because it removes all overlap and can serialize long network requests unnecessarily.

### Keep cached thread pool

Rejected because it leaves active thread count unbounded during bursts.

### Bounded queue with rejection

Rejected because it introduces a new user-visible failure mode.

### CallerRunsPolicy

Rejected because `TranscribeHelper.sendRequest(...)` can be invoked from UI paths; running a heavy transcription task on the caller could block the UI.

### One executor per provider

Rejected because it permits aggregate concurrency to multiply across providers.

### Semaphore around current cached pool

Rejected because cached-pool threads could still be created and block waiting for permits, so thread amplification would remain.

### OkHttp async enqueue instead of synchronous execute

Rejected because it changes callback/lifecycle semantics beyond the scheduling problem.

## Verification gate

Before P7 is considered verified:

1. RED executor policy/source tests are observed before runtime changes;
2. focused P7 tests pass after implementation;
3. full `:TMessagesProj:testNormalDebugUnitTest --no-configuration-cache` passes fresh;
4. `:TMessagesProj:compileNormalDebugJavaWithJavac --no-configuration-cache` passes under JDK 21;
5. static gate proves no `newCachedThreadPool()` remains in `TMessagesProj/src`;
6. static gate proves exactly one transcription executor factory/pool exists;
7. executor policy is exactly max concurrency 2 with core timeout enabled;
8. all three provider submit sites remain;
9. `TranscribeButton.java` SHA-256 matches P6;
10. `HttpClient.kt` SHA-256 matches P6;
11. `NaConfig.kt` SHA-256 matches P6;
12. provider request method bodies are unchanged except executor declaration/use needed by P7;
13. no P1–P6 protected runtime file changes;
14. `git diff --check` passes;
15. implementation branch is pushed and local/remote HEAD match.

## Success criteria

P7 succeeds when custom-AI transcription can no longer scale active heavy work one worker per simultaneous request.

The strongest accepted pre-device statement is:

> Custom-AI transcription uses a two-worker bounded executor with FIFO queuing and idle worker timeout, limiting simultaneous full-audio processing and blocking transcription requests while preserving provider/request/callback semantics.

No quantitative battery, thermal, memory, or latency claim is made without device measurement.

## Integration policy

P7 is developed from verified P6 HEAD and committed independently.

This design phase does not authorize implementation, merge into `dev`, release, APK installation, or device performance claims.

After design approval, a separate implementation plan will be written and presented for Plan Gate approval.
