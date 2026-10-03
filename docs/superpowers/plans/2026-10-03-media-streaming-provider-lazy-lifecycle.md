# P5 — Lazy MediaStreamingProvider Lifecycle Implementation Plan

**Status:** Draft — awaiting plan approval
**Design:** `docs/superpowers/specs/2026-10-03-media-streaming-provider-lazy-lifecycle-design.md`
**Implementation base:** `57931554aec829fd6c5ceb6a2e2b13010bcf3170`
**Design commit:** `d2a44d50f961eb226ef69bfd2cec3e61170001e4`

## Goal

Delay creation of the existing provider-local `HandlerThread("MediaStreamingProvider")` until the first valid streaming `openFile(..., "r")`, while preserving all provider, URI, streaming callback, and shutdown behavior.

The implementation is intentionally narrow:

- runtime change limited to `MediaStreamingProvider.java`;
- no manifest change;
- no PhotoViewer change;
- no StorageManagerCompat change;
- no new executor/thread/timer/polling mechanism beyond lazily creating the existing HandlerThread;
- no measured performance claim before device validation.

## Task 1 — RED lifecycle contract test

Create:

`TMessagesProj/src/test/java/tw/nekomimi/nekogram/streaming/MediaStreamingProviderLazyLifecycleShapeTest.java`

The source-contract test must fail on the current P4B implementation and assert:

1. `onCreate()` contains no `new HandlerThread`, `.start()`, or `new Handler`;
2. `onCreate()` still returns `true`;
3. a `private synchronized Handler getCallbackHandler()` helper exists;
4. the helper preserves thread name `MediaStreamingProvider`;
5. helper starts the thread before calling `getLooper()`;
6. helper reuses an existing handler rather than creating a thread per call;
7. `openFile()` checks context-null before lazy handler acquisition;
8. `openFile()` rejects non-read mode before lazy handler acquisition;
9. `openFile()` passes the lazy handler to `openProxyFileDescriptor`;
10. `shutdown()` checks for null before `quit()` and clears both fields.

Run only the new test and record RED:

```bash
./gradlew :TMessagesProj:testNormalDebugUnitTest \
  --tests 'tw.nekomimi.nekogram.streaming.MediaStreamingProviderLazyLifecycleShapeTest' \
  --no-configuration-cache
```

Expected RED reason: current `onCreate()` eagerly starts the worker and no lazy helper exists.

## Task 2 — Minimal lazy lifecycle implementation

Modify only:

`TMessagesProj/src/main/java/tw/nekomimi/nekogram/streaming/MediaStreamingProvider.java`

### onCreate

Change from eager worker construction to:

```java
@Override
public boolean onCreate() {
    return true;
}
```

### Lazy handler helper

Add:

```java
private synchronized Handler getCallbackHandler()
```

Contract:

- return `callbackHandler` immediately if non-null;
- otherwise create `HandlerThread("MediaStreamingProvider")`;
- call `start()`;
- construct `Handler(thread.getLooper())`;
- publish `callbackThread` and `callbackHandler`;
- return the handler.

Do not create a second worker per file descriptor.

### openFile

Preserve validation order exactly:

1. obtain context;
2. if context is null, return null;
3. if mode is not `"r"`, throw `SecurityException`;
4. construct existing `ProxyFileDescriptorCallback`;
5. obtain existing `StorageManagerCompat`;
6. call `getCallbackHandler()`;
7. pass that handler to `openProxyFileDescriptor`;
8. preserve existing `IOException` → `FileNotFoundException` translation.

Do not alter URI/data-source/callback code.

### shutdown

Make cleanup synchronized/null-safe:

- if `callbackThread != null`, call `quit()`;
- set `callbackHandler = null`;
- set `callbackThread = null`.

Do not add join/wait/idle timeout/restart behavior.

### Focused verification

Run the new focused test until GREEN.

Then run:

```bash
./gradlew :TMessagesProj:compileNormalDebugJavaWithJavac --no-configuration-cache
```

Run `git diff --check`.

### Commit

```text
perf: lazily start media streaming provider worker
```

## Task 3 — Full regression and preservation gate

Run fresh:

```bash
./gradlew :TMessagesProj:testNormalDebugUnitTest --no-configuration-cache
./gradlew :TMessagesProj:compileNormalDebugJavaWithJavac --no-configuration-cache
```

Both must report `BUILD SUCCESSFUL`.

Run a deterministic static gate against P4B base `57931554...` that verifies:

- only `MediaStreamingProvider.java` changed under `TMessagesProj/src/main/`;
- manifest SHA-256 equals P4B;
- `PhotoViewer.java` SHA-256 equals P4B;
- `StorageManagerCompat.java` SHA-256 equals P4B;
- `getStreamingUri(...)` method body equals P4B;
- `openForStreaming(...)` method body equals P4B;
- `ProxyFileDescriptorCallback` code equals P4B;
- provider authority remains `${applicationId}.streaming`;
- `onCreate()` contains no worker construction;
- exactly one `new HandlerThread("MediaStreamingProvider")` remains in the provider source and it is inside the lazy helper;
- no executor, timer, polling loop, HandlerThread restart loop, or additional persistent worker is added;
- `openFile()` validation occurs before lazy worker acquisition;
- `shutdown()` remains null-safe and retains `quit()`;
- `git diff --check` passes.

Review the complete diff for unrelated formatting/refactor churn.

## Task 4 — Record evidence and publish implementation branch

After all Task 3 evidence is fresh:

- update this checklist;
- mark this plan `Executed — verified 2026-10-03`;
- commit the plan evidence only after verification;
- push branch:

```text
perf/v2-p5-lazy-streaming-provider
```

Verify local HEAD == remote HEAD.

Do not merge into `dev`, release, build/install APK, or claim device-level battery/thermal gains under this approval.

## Execution checklist

- [ ] Task 1 RED test observed.
- [ ] Task 2 lazy worker implementation GREEN.
- [ ] Focused P5 test passed.
- [ ] Java compile passed.
- [ ] Task 2 committed.
- [ ] Full unit suite passed fresh.
- [ ] Fresh Java compile passed.
- [ ] Whole-P5 static preservation gate passed.
- [ ] Manifest unchanged from P4B.
- [ ] PhotoViewer unchanged from P4B.
- [ ] StorageManagerCompat unchanged from P4B.
- [ ] No unrelated runtime file changed.
- [ ] No new persistent background resource added.
- [ ] git diff --check passed.
- [ ] Implementation branch pushed.
- [ ] Local/remote implementation HEAD match.
- [ ] No integration/release/deploy/device gate crossed.

## Stop conditions

Stop and return to design if implementation requires:

- changing the provider manifest;
- changing PhotoViewer behavior;
- changing StorageManagerCompat behavior;
- changing streaming URI/MIME/permission semantics;
- replacing HandlerThread with another executor model;
- adding idle timers or periodic lifecycle work;
- adding per-stream workers;
- touching runtime files outside MediaStreamingProvider.java.

## Approval boundary

Plan approval authorizes implementation and verification of Tasks 1–4 on the dedicated P5 branch.

It does **not** authorize merge into `dev`, release, APK installation, or production/device rollout.
